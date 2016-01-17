package uncore

import Chisel._
import junctions._
import cde.Parameters

/** Utilities for safely wrapping a *UncachedTileLink by pinning probe.ready and release.valid low */
object TileLinkIOWrapper {
  def apply(tl: ClientUncachedTileLinkIO)(implicit p: Parameters): ClientTileLinkIO = {
    val conv = Module(new ClientTileLinkIOWrapper)
    conv.io.in <> tl
    conv.io.out
  }
  def apply(tl: UncachedTileLinkIO)(implicit p: Parameters): TileLinkIO = {
    val conv = Module(new TileLinkIOWrapper)
    conv.io.in <> tl
    conv.io.out
  }
  def apply(tl: ClientTileLinkIO): ClientTileLinkIO = tl
  def apply(tl: TileLinkIO): TileLinkIO = tl
}

class TileLinkIOWrapper(implicit p: Parameters) extends TLModule()(p) {
  val io = new Bundle {
    val in = new UncachedTileLinkIO().flip
    val out = new TileLinkIO
  }
  io.out.acquire <> io.in.acquire
  io.in.grant <> io.out.grant
  io.out.finish <> io.in.finish
  io.out.probe.ready := Bool(true)
  io.out.release.valid := Bool(false)
}

class ClientTileLinkIOWrapper(implicit p: Parameters) extends TLModule()(p) {
  val io = new Bundle {
    val in = new ClientUncachedTileLinkIO().flip
    val out = new ClientTileLinkIO
  }
  io.out.acquire <> io.in.acquire
  io.in.grant <> io.out.grant
  io.out.probe.ready := Bool(true)
  io.out.release.valid := Bool(false)
}

/** A helper module that automatically issues [[uncore.Finish]] messages in repsonse
  * to [[uncore.Grant]] that it receives from a manager and forwards to a client
  */
class FinishUnit(srcId: Int = 0, outstanding: Int = 2)(implicit p: Parameters) extends TLModule()(p)
    with HasDataBeatCounters {
  val io = new Bundle {
    val grant = Decoupled(new LogicalNetworkIO(new Grant)).flip
    val refill = Decoupled(new Grant)
    val finish = Decoupled(new LogicalNetworkIO(new Finish))
    val ready = Bool(OUTPUT)
  }

  val g = io.grant.bits.payload

  if(tlNetworkPreservesPointToPointOrdering) {
    io.finish.valid := Bool(false)
    io.refill.valid := io.grant.valid
    io.refill.bits := g
    io.grant.ready := io.refill.ready
    io.ready := Bool(true)
  } else {
    // We only want to send Finishes after we have collected all beats of
    // a multibeat Grant. But Grants from multiple managers or transactions may
    // get interleaved, so we could need a counter for each.
    val done = if(tlNetworkDoesNotInterleaveBeats) {
      connectIncomingDataBeatCounterWithHeader(io.grant)
    } else {
      val entries = 1 << tlClientXactIdBits
      def getId(g: LogicalNetworkIO[Grant]) = g.payload.client_xact_id
      assert(getId(io.grant.bits) <= UInt(entries), "Not enough grant beat counters, only " + entries + " entries.")
      connectIncomingDataBeatCountersWithHeader(io.grant, entries, getId).reduce(_||_)
    }
    val q = Module(new FinishQueue(outstanding))
    q.io.enq.valid := io.grant.fire() && g.requiresAck() && (!g.hasMultibeatData() || done)
    q.io.enq.bits.fin := g.makeFinish()
    q.io.enq.bits.dst := io.grant.bits.header.src

    io.finish.bits.header.src := UInt(srcId)
    io.finish.bits.header.dst := q.io.deq.bits.dst
    io.finish.bits.payload := q.io.deq.bits.fin
    io.finish.valid := q.io.deq.valid
    q.io.deq.ready := io.finish.ready

    io.refill.valid := (q.io.enq.ready || !g.requiresAck()) && io.grant.valid
    io.refill.bits := g
    io.grant.ready := (q.io.enq.ready || !g.requiresAck()) && io.refill.ready
    io.ready := q.io.enq.ready
  }
}

class FinishQueueEntry(implicit p: Parameters) extends TLBundle()(p) {
    val fin = new Finish
    val dst = UInt(width = log2Up(p(LNEndpoints)))
}

class FinishQueue(entries: Int)(implicit p: Parameters) extends Queue(new FinishQueueEntry()(p), entries)

/** A port to convert [[uncore.ClientTileLinkIO]].flip into [[uncore.TileLinkIO]]
  *
  * Creates network headers for [[uncore.Acquire]] and [[uncore.Release]] messages,
  * calculating header.dst and filling in header.src.
  * Strips headers from [[uncore.Probe Probes]].
  * Responds to [[uncore.Grant]] by automatically issuing [[uncore.Finish]] to the granting managers.
  *
  * @param clientId network port id of this agent
  * @param addrConvert how a physical address maps to a destination manager port id
  */
class ClientTileLinkNetworkPort(clientId: Int, addrConvert: UInt => UInt)
                               (implicit p: Parameters) extends TLModule()(p) {
  val io = new Bundle {
    val client = new ClientTileLinkIO().flip
    val network = new TileLinkIO
  }

  val finisher = Module(new FinishUnit(clientId))
  finisher.io.grant <> io.network.grant
  io.network.finish <> finisher.io.finish

  val acq_with_header = ClientTileLinkHeaderCreator(io.client.acquire, clientId, addrConvert)
  val rel_with_header = ClientTileLinkHeaderCreator(io.client.release, clientId, addrConvert)
  val prb_without_header = DecoupledLogicalNetworkIOUnwrapper(io.network.probe)
  val gnt_without_header = finisher.io.refill

  io.network.acquire.bits := acq_with_header.bits
  io.network.acquire.valid := acq_with_header.valid && finisher.io.ready
  acq_with_header.ready := io.network.acquire.ready && finisher.io.ready
  io.network.release <> rel_with_header
  io.client.probe <> prb_without_header
  io.client.grant <> gnt_without_header
}

object ClientTileLinkHeaderCreator {
  def apply[T <: ClientToManagerChannel with HasCacheBlockAddress](
        in: DecoupledIO[T],
        clientId: Int,
        addrConvert: UInt => UInt)
      (implicit p: Parameters): DecoupledIO[LogicalNetworkIO[T]] = {
    val out = Wire(new DecoupledIO(new LogicalNetworkIO(in.bits)))
    out.bits.payload := in.bits
    out.bits.header.src := UInt(clientId)
    out.bits.header.dst := addrConvert(in.bits.addr_block)
    out.valid := in.valid
    in.ready := out.ready
    out
  }
}

/** A port to convert [[uncore.ManagerTileLinkIO]].flip into [[uncore.TileLinkIO]].flip
  *
  * Creates network headers for [[uncore.Probe]] and [[uncore.Grant]] messagess,
  * calculating header.dst and filling in header.src.
  * Strips headers from [[uncore.Acquire]], [[uncore.Release]] and [[uncore.Finish]],
  * but supplies client_id instead.
  *
  * @param managerId the network port id of this agent
  * @param idConvert how a sharer id maps to a destination client port id
  */
class ManagerTileLinkNetworkPort(managerId: Int, idConvert: UInt => UInt)
                                (implicit p: Parameters) extends TLModule()(p) {
  val io = new Bundle {
    val manager = new ManagerTileLinkIO().flip
    val network = new TileLinkIO().flip
  }
  io.network.grant <> ManagerTileLinkHeaderCreator(io.manager.grant, managerId, (u: UInt) => u)
  io.network.probe <> ManagerTileLinkHeaderCreator(io.manager.probe, managerId, idConvert)
  io.manager.acquire.bits.client_id := io.network.acquire.bits.header.src
  io.manager.acquire <> DecoupledLogicalNetworkIOUnwrapper(io.network.acquire)
  io.manager.release.bits.client_id := io.network.release.bits.header.src
  io.manager.release <> DecoupledLogicalNetworkIOUnwrapper(io.network.release)
  io.manager.finish <> DecoupledLogicalNetworkIOUnwrapper(io.network.finish)
}

object ManagerTileLinkHeaderCreator {
  def apply[T <: ManagerToClientChannel with HasClientId](
        in: DecoupledIO[T],
        managerId: Int,
        idConvert: UInt => UInt)
      (implicit p: Parameters): DecoupledIO[LogicalNetworkIO[T]] = {
    val out = Wire(new DecoupledIO(new LogicalNetworkIO(in.bits)))
    out.bits.payload := in.bits
    out.bits.header.src := UInt(managerId)
    out.bits.header.dst := idConvert(in.bits.client_id)
    out.valid := in.valid
    in.ready := out.ready
    out
  }
}

/** Utility trait containing wiring functions to keep track of how many data beats have 
  * been sent or recieved over a particular [[uncore.TileLinkChannel]] or pair of channels. 
  *
  * Won't count message types that don't have data. 
  * Used in [[uncore.XactTracker]] and [[uncore.FinishUnit]].
  */
trait HasDataBeatCounters {
  type HasBeat = TileLinkChannel with HasTileLinkBeatId

  /** Returns the current count on this channel and when a message is done
    * @param inc increment the counter (usually .valid or .fire())
    * @param data the actual channel data
    * @param beat count to return for single-beat messages
    */
  def connectDataBeatCounter[S <: TileLinkChannel](inc: Bool, data: S, beat: UInt) = {
    val multi = data.hasMultibeatData()
    val (multi_cnt, multi_done) = Counter(inc && multi, data.tlDataBeats)
    val cnt = Mux(multi, multi_cnt, beat)
    val done = Mux(multi, multi_done, inc)
    (cnt, done)
  }

  /** Counter for beats on outgoing [[chisel.DecoupledIO]] */
  def connectOutgoingDataBeatCounter[T <: TileLinkChannel](
      out: DecoupledIO[T],
      beat: UInt = UInt(0)): (UInt, Bool) =
    connectDataBeatCounter(out.fire(), out.bits, beat)

  /** Returns done but not cnt. Use the addr_beat subbundle instead of cnt for beats on 
    * incoming channels in case of network reordering.
    */
  def connectIncomingDataBeatCounter[T <: TileLinkChannel](in: DecoupledIO[T]): Bool =
    connectDataBeatCounter(in.fire(), in.bits, UInt(0))._2

  /** Counter for beats on incoming DecoupledIO[LogicalNetworkIO[]]s returns done */
  def connectIncomingDataBeatCounterWithHeader[T <: TileLinkChannel](in: DecoupledIO[LogicalNetworkIO[T]]): Bool =
    connectDataBeatCounter(in.fire(), in.bits.payload, UInt(0))._2

  /** If the network might interleave beats from different messages, we need a Vec of counters,
    * one for every outstanding message id that might be interleaved.
    *
    * @param getId mapping from Message to counter id
    */
  def connectIncomingDataBeatCountersWithHeader[T <: TileLinkChannel with HasClientTransactionId](
      in: DecoupledIO[LogicalNetworkIO[T]],
      entries: Int,
      getId: LogicalNetworkIO[T] => UInt): Vec[Bool] = {
    Vec((0 until entries).map { i =>
      connectDataBeatCounter(in.fire() && getId(in.bits) === UInt(i), in.bits.payload, UInt(0))._2 
    })
  }

  /** Provides counters on two channels, as well a meta-counter that tracks how many
    * messages have been sent over the up channel but not yet responded to over the down channel
    *
    * @param max max number of outstanding ups with no down
    * @param up outgoing channel
    * @param down incoming channel
    * @param beat overrides cnts on single-beat messages
    * @param track whether up's message should be tracked
    * @return a tuple containing whether their are outstanding messages, up's count,
    *         up's done, down's count, down's done
    */
  def connectTwoWayBeatCounter[T <: TileLinkChannel, S <: TileLinkChannel](
      max: Int,
      up: DecoupledIO[T],
      down: DecoupledIO[S],
      beat: UInt = UInt(0),
      track: T => Bool = (t: T) => Bool(true)): (Bool, UInt, Bool, UInt, Bool) = {
    val (up_idx, up_done) = connectDataBeatCounter(up.fire(), up.bits, beat)
    val (down_idx, down_done) = connectDataBeatCounter(down.fire(), down.bits, beat)
    val do_inc = up_done && track(up.bits)
    val do_dec = down_done
    val cnt = TwoWayCounter(do_inc, do_dec, max)
    (cnt > UInt(0), up_idx, up_done, down_idx, down_done)
  }
}

class ClientTileLinkIOUnwrapper(implicit p: Parameters) extends TLModule()(p) {
  val io = new Bundle {
    val in = new ClientTileLinkIO().flip
    val out = new ClientUncachedTileLinkIO
  }

  def needsRoqEnq(channel: HasTileLinkData): Bool =
    !channel.hasMultibeatData() || channel.addr_beat === UInt(0)

  def needsRoqDeq(channel: HasTileLinkData): Bool =
    !channel.hasMultibeatData() || channel.addr_beat === UInt(tlDataBeats - 1)

  val acqArb = Module(new LockingRRArbiter(new Acquire, 2, tlDataBeats,
    Some((acq: Acquire) => acq.hasMultibeatData())))

  val acqRoq = Module(new ReorderQueue(
    Bool(), tlClientXactIdBits, tlMaxClientsPerPort))

  val relRoq = Module(new ReorderQueue(
    Bool(), tlClientXactIdBits, tlMaxClientsPerPort))

  val iacq = io.in.acquire.bits
  val irel = io.in.release.bits
  val ognt = io.out.grant.bits

  val acq_roq_enq = needsRoqEnq(iacq)
  val rel_roq_enq = needsRoqEnq(irel)

  val acq_roq_ready = !acq_roq_enq || acqRoq.io.enq.ready
  val rel_roq_ready = !rel_roq_enq || relRoq.io.enq.ready

  val acq_helper = DecoupledHelper(
    io.in.acquire.valid,
    acq_roq_ready,
    acqArb.io.in(0).ready)

  val rel_helper = DecoupledHelper(
    io.in.release.valid,
    rel_roq_ready,
    acqArb.io.in(1).ready)

  acqRoq.io.enq.valid := acq_helper.fire(acq_roq_ready, acq_roq_enq)
  acqRoq.io.enq.bits.data := iacq.isBuiltInType()
  acqRoq.io.enq.bits.tag := iacq.client_xact_id

  acqArb.io.in(0).valid := acq_helper.fire(acqArb.io.in(0).ready)
  acqArb.io.in(0).bits := Acquire(
    is_builtin_type = Bool(true),
    a_type = Mux(iacq.isBuiltInType(),
      iacq.a_type, Acquire.getBlockType),
    client_xact_id = iacq.client_xact_id,
    addr_block = iacq.addr_block,
    addr_beat = iacq.addr_beat,
    data = iacq.data,
    union = Mux(iacq.isBuiltInType(),
      iacq.union, Cat(MT_Q, M_XRD, Bool(true))))
  io.in.acquire.ready := acq_helper.fire(io.in.acquire.valid)

  relRoq.io.enq.valid := rel_helper.fire(rel_roq_ready, rel_roq_enq)
  relRoq.io.enq.bits.data := irel.isVoluntary()
  relRoq.io.enq.bits.tag := irel.client_xact_id

  acqArb.io.in(1).valid := rel_helper.fire(acqArb.io.in(1).ready)
  acqArb.io.in(1).bits := PutBlock(
    client_xact_id = irel.client_xact_id,
    addr_block = irel.addr_block,
    addr_beat = irel.addr_beat,
    data = irel.data,
    wmask = Acquire.fullWriteMask)
  io.in.release.ready := rel_helper.fire(io.in.release.valid)

  io.out.acquire <> acqArb.io.out

  acqRoq.io.deq.valid := io.out.grant.fire() && needsRoqDeq(ognt)
  acqRoq.io.deq.tag := ognt.client_xact_id

  relRoq.io.deq.valid := io.out.grant.fire() && needsRoqDeq(ognt)
  relRoq.io.deq.tag := ognt.client_xact_id

  val gnt_builtin = acqRoq.io.deq.data
  val gnt_voluntary = relRoq.io.deq.data

  val acq_grant = Grant(
    is_builtin_type = gnt_builtin,
    g_type = Mux(gnt_builtin, ognt.g_type, tlCoh.getExclusiveGrantType),
    client_xact_id = ognt.client_xact_id,
    manager_xact_id = ognt.manager_xact_id,
    addr_beat = ognt.addr_beat,
    data = ognt.data)

  val rel_grant = Grant(
    is_builtin_type = Bool(true),
    g_type = Mux(gnt_voluntary, Grant.voluntaryAckType, ognt.g_type),
    client_xact_id = ognt.client_xact_id,
    manager_xact_id = ognt.manager_xact_id,
    addr_beat = ognt.addr_beat,
    data = ognt.data)

  io.in.grant.valid := io.out.grant.valid
  io.in.grant.bits := Mux(acqRoq.io.deq.matches, acq_grant, rel_grant)
  io.out.grant.ready := io.in.grant.ready

  io.in.probe.valid := Bool(false)
}

class NastiIOTileLinkIOConverterInfo(implicit p: Parameters) extends TLBundle()(p) {
  val addr_beat = UInt(width = tlBeatAddrBits)
  val byteOff = UInt(width = tlByteAddrBits)
  val subblock = Bool()
}

class NastiIOTileLinkIOConverter(implicit p: Parameters) extends TLModule()(p)
    with HasNastiParameters {
  val io = new Bundle {
    val tl = new ClientUncachedTileLinkIO().flip
    val nasti = new NastiIO
  }

  private def opSizeToXSize(ops: UInt) = MuxLookup(ops, UInt("b111"), Seq(
    MT_B  -> UInt(0),
    MT_BU -> UInt(0),
    MT_H  -> UInt(1),
    MT_HU -> UInt(1),
    MT_W  -> UInt(2),
    MT_D  -> UInt(3),
    MT_Q  -> UInt(log2Up(tlDataBytes))))

  val dataBits = tlDataBits*tlDataBeats 
  require(tlDataBits == nastiXDataBits, "Data sizes between LLC and MC don't agree") // TODO: remove this restriction
  require(tlDataBeats < (1 << nastiXLenBits), "Can't have that many beats")
  require(tlClientXactIdBits <= nastiXIdBits, "NastiIO converter is going truncate tags: " + tlClientXactIdBits + " > " + nastiXIdBits)

  val has_data = io.tl.acquire.bits.hasData()

  val is_subblock = io.tl.acquire.bits.isSubBlockType()
  val is_multibeat = io.tl.acquire.bits.hasMultibeatData()
  val (tl_cnt_out, tl_wrap_out) = Counter(
    io.tl.acquire.fire() && is_multibeat, tlDataBeats)

  val get_valid = io.tl.acquire.valid && !has_data
  val put_valid = io.tl.acquire.valid && has_data

  // Reorder queue saves extra information needed to send correct
  // grant back to TL client
  val roq = Module(new ReorderQueue(
    new NastiIOTileLinkIOConverterInfo,
    nastiRIdBits, tlMaxClientsPerPort))

  // For Get/GetBlock, make sure Reorder queue can accept new entry
  val get_helper = DecoupledHelper(
    get_valid,
    roq.io.enq.ready,
    io.nasti.ar.ready)

  val w_inflight = Reg(init = Bool(false))

  // For Put/PutBlock, make sure aw and w channel are both ready before
  // we send the first beat
  val aw_ready = w_inflight || io.nasti.aw.ready
  val put_helper = DecoupledHelper(
    put_valid,
    aw_ready,
    io.nasti.w.ready)

  val (nasti_cnt_out, nasti_wrap_out) = Counter(
    io.nasti.r.fire() && !roq.io.deq.data.subblock, tlDataBeats)

  roq.io.enq.valid := get_helper.fire(roq.io.enq.ready)
  roq.io.enq.bits.tag := io.nasti.ar.bits.id
  roq.io.enq.bits.data.addr_beat := io.tl.acquire.bits.addr_beat
  roq.io.enq.bits.data.byteOff := io.tl.acquire.bits.addr_byte()
  roq.io.enq.bits.data.subblock := is_subblock
  roq.io.deq.valid := io.nasti.r.fire() && (nasti_wrap_out || roq.io.deq.data.subblock)
  roq.io.deq.tag := io.nasti.r.bits.id

  // Decompose outgoing TL Acquires into Nasti address and data channels
  io.nasti.ar.valid := get_helper.fire(io.nasti.ar.ready)
  io.nasti.ar.bits := NastiReadAddressChannel(
    id = io.tl.acquire.bits.client_xact_id,
    addr = io.tl.acquire.bits.full_addr(),
    size = Mux(is_subblock,
      opSizeToXSize(io.tl.acquire.bits.op_size()),
      UInt(log2Ceil(tlDataBytes))),
    len = Mux(is_subblock, UInt(0), UInt(tlDataBeats - 1)))

  io.nasti.aw.valid := put_helper.fire(aw_ready, !w_inflight)
  io.nasti.aw.bits := NastiWriteAddressChannel(
    id = io.tl.acquire.bits.client_xact_id,
    addr = io.tl.acquire.bits.full_addr(),
    size = UInt(log2Ceil(tlDataBytes)),
    len = Mux(is_multibeat, UInt(tlDataBeats - 1), UInt(0)))

  io.nasti.w.valid := put_helper.fire(io.nasti.w.ready)
  io.nasti.w.bits := NastiWriteDataChannel(
    data = io.tl.acquire.bits.data,
    strb = io.tl.acquire.bits.wmask(),
    last = tl_wrap_out || (io.tl.acquire.fire() && is_subblock))

  io.tl.acquire.ready := Mux(has_data,
    put_helper.fire(put_valid),
    get_helper.fire(get_valid))

  when (!w_inflight && io.tl.acquire.fire() && is_multibeat) {
    w_inflight := Bool(true)
  }

  when (w_inflight) {
    when (tl_wrap_out) { w_inflight := Bool(false) }
  }

  // Aggregate incoming NASTI responses into TL Grants
  val (tl_cnt_in, tl_wrap_in) = Counter(
    io.tl.grant.fire() && io.tl.grant.bits.hasMultibeatData(), tlDataBeats)
  val gnt_arb = Module(new Arbiter(new GrantToDst, 2))
  io.tl.grant <> gnt_arb.io.out

  val r_aligned_data = Mux(roq.io.deq.data.subblock,
    io.nasti.r.bits.data << Cat(roq.io.deq.data.byteOff, UInt(0, 3)),
    io.nasti.r.bits.data)

  gnt_arb.io.in(0).valid := io.nasti.r.valid
  io.nasti.r.ready := gnt_arb.io.in(0).ready
  gnt_arb.io.in(0).bits := Grant(
    is_builtin_type = Bool(true),
    g_type = Mux(roq.io.deq.data.subblock,
      Grant.getDataBeatType, Grant.getDataBlockType),
    client_xact_id = io.nasti.r.bits.id,
    manager_xact_id = UInt(0),
    addr_beat = Mux(roq.io.deq.data.subblock, roq.io.deq.data.addr_beat, tl_cnt_in),
    data = r_aligned_data)

  gnt_arb.io.in(1).valid := io.nasti.b.valid
  io.nasti.b.ready := gnt_arb.io.in(1).ready
  gnt_arb.io.in(1).bits := Grant(
    is_builtin_type = Bool(true),
    g_type = Grant.putAckType,
    client_xact_id = io.nasti.b.bits.id,
    manager_xact_id = UInt(0),
    addr_beat = UInt(0),
    data = Bits(0))

  assert(!io.nasti.r.valid || io.nasti.r.bits.resp === UInt(0), "NASTI read error")
  assert(!io.nasti.b.valid || io.nasti.b.bits.resp === UInt(0), "NASTI write error")
}

class TileLinkIONarrower(innerTLId: String, outerTLId: String)
    (implicit p: Parameters) extends TLModule()(p) {

  val innerParams = p(TLKey(innerTLId))
  val outerParams = p(TLKey(outerTLId)) 
  val innerDataBeats = innerParams.dataBeats
  val innerDataBits = innerParams.dataBitsPerBeat
  val innerWriteMaskBits = innerParams.writeMaskBits
  val innerByteAddrBits = log2Up(innerWriteMaskBits)
  val outerDataBeats = outerParams.dataBeats
  val outerDataBits = outerParams.dataBitsPerBeat
  val outerWriteMaskBits = outerParams.writeMaskBits
  val outerByteAddrBits = log2Up(outerWriteMaskBits)
  val outerBeatAddrBits = log2Up(outerDataBeats)
  val outerBlockOffset = outerBeatAddrBits + outerByteAddrBits
  val outerMaxClients = outerParams.maxClientsPerPort
  val outerIdBits = log2Up(outerParams.maxClientXacts * outerMaxClients)

  require(outerDataBeats >= innerDataBeats)
  require(outerDataBeats % innerDataBeats == 0)
  require(outerDataBits <= innerDataBits)
  require(outerDataBits * outerDataBeats == innerDataBits * innerDataBeats)

  val factor = outerDataBeats / innerDataBeats

  val io = new Bundle {
    val in = new ClientUncachedTileLinkIO()(p.alterPartial({case TLId => innerTLId})).flip
    val out = new ClientUncachedTileLinkIO()(p.alterPartial({case TLId => outerTLId}))
  }

  if (factor > 1) {
    val iacq = io.in.acquire.bits
    val ognt = io.out.grant.bits

    val stretch = iacq.a_type === Acquire.putBlockType
    val shrink = iacq.a_type === Acquire.getBlockType
    val smallput = iacq.a_type === Acquire.putType
    val smallget = iacq.a_type === Acquire.getType

    val acq_data_buffer = Reg(UInt(width = innerDataBits))
    val acq_wmask_buffer = Reg(UInt(width = innerWriteMaskBits))
    val acq_client_id = Reg(iacq.client_xact_id)
    val acq_addr_block = Reg(iacq.addr_block)
    val acq_addr_beat = Reg(iacq.addr_beat)
    val oacq_ctr = Counter(factor)

    // this part of the address shifts from the inner byte address 
    // to the outer beat address
    val readshift = iacq.full_addr()(innerByteAddrBits - 1, outerByteAddrBits)
    val outer_beat_addr = iacq.full_addr()(outerBlockOffset - 1, outerByteAddrBits)
    val outer_byte_addr = iacq.full_addr()(outerByteAddrBits - 1, 0)

    val mask_chunks = Vec.tabulate(factor) { i =>
      val lsb = i * outerWriteMaskBits
      val msb = (i + 1) * outerWriteMaskBits - 1
      iacq.wmask()(msb, lsb)
    }

    val data_chunks = Vec.tabulate(factor) { i =>
      val lsb = i * outerDataBits
      val msb = (i + 1) * outerDataBits - 1
      iacq.data(msb, lsb)
    }

    val beat_sel = Cat(mask_chunks.map(mask => mask.orR).reverse)

    val smallput_data = Mux1H(beat_sel, data_chunks)
    val smallput_wmask = Mux1H(beat_sel, mask_chunks)
    val smallput_beat = Cat(iacq.addr_beat, PriorityEncoder(beat_sel))

    assert(!io.in.acquire.valid || !smallput || PopCount(beat_sel) <= UInt(1),
      "Can't perform Put wider than outer width")

    val read_size_ok = MuxLookup(iacq.op_size(), Bool(false), Seq(
      MT_B  -> Bool(true),
      MT_BU -> Bool(true),
      MT_H  -> Bool(outerDataBits >= 16),
      MT_HU -> Bool(outerDataBits >= 16),
      MT_W  -> Bool(outerDataBits >= 32),
      MT_D  -> Bool(outerDataBits >= 64),
      MT_Q  -> Bool(false)))

    assert(!io.in.acquire.valid || !smallget || read_size_ok,
      "Can't perform Get wider than outer width")

    val outerConfig = p.alterPartial({ case TLId => outerTLId })
    val innerConfig = p.alterPartial({ case TLId => innerTLId })

    val get_block_acquire = GetBlock(
      client_xact_id = iacq.client_xact_id,
      addr_block = iacq.addr_block,
      alloc = iacq.allocate())(outerConfig)

    val put_block_acquire = PutBlock(
      client_xact_id = acq_client_id,
      addr_block = acq_addr_block,
      addr_beat = if (factor > 1)
                    Cat(acq_addr_beat, oacq_ctr.value)
                  else acq_addr_beat,
      data = acq_data_buffer(outerDataBits - 1, 0),
      wmask = acq_wmask_buffer(outerWriteMaskBits - 1, 0))(outerConfig)

    val get_acquire = Get(
      client_xact_id = iacq.client_xact_id,
      addr_block = iacq.addr_block,
      addr_beat = outer_beat_addr,
      addr_byte = outer_byte_addr,
      operand_size = iacq.op_size(),
      alloc = iacq.allocate())(outerConfig)

    val put_acquire = Put(
      client_xact_id = iacq.client_xact_id,
      addr_block = iacq.addr_block,
      addr_beat = smallput_beat,
      data = smallput_data,
      wmask = Some(smallput_wmask))(outerConfig)

    val sending_put = Reg(init = Bool(false))

    val pass_valid = io.in.acquire.valid && !stretch && !smallget
    val smallget_valid = smallget && io.in.acquire.valid

    val smallget_roq = Module(new ReorderQueue(
      readshift, outerIdBits, outerMaxClients))

    val smallget_helper = DecoupledHelper(
      smallget_valid,
      smallget_roq.io.enq.ready,
      io.out.acquire.ready)

    smallget_roq.io.enq.valid := smallget_helper.fire(
      smallget_roq.io.enq.ready, !sending_put)
    smallget_roq.io.enq.bits.data := readshift
    smallget_roq.io.enq.bits.tag := iacq.client_xact_id

    io.out.acquire.bits := MuxBundle(Wire(io.out.acquire.bits, init=iacq), Seq(
      (sending_put, put_block_acquire),
      (shrink, get_block_acquire),
      (smallput, put_acquire),
      (smallget, get_acquire)))
    io.out.acquire.valid := sending_put || pass_valid ||
      smallget_helper.fire(io.out.acquire.ready)
    io.in.acquire.ready := !sending_put && (stretch ||
      (!smallget && io.out.acquire.ready) ||
      smallget_helper.fire(smallget_valid))

    when (io.in.acquire.fire() && stretch) {
      acq_data_buffer := iacq.data
      acq_wmask_buffer := iacq.wmask()
      acq_client_id := iacq.client_xact_id
      acq_addr_block := iacq.addr_block
      acq_addr_beat := iacq.addr_beat
      sending_put := Bool(true)
    }

    when (sending_put && io.out.acquire.ready) {
      acq_data_buffer := acq_data_buffer >> outerDataBits
      acq_wmask_buffer := acq_wmask_buffer >> outerWriteMaskBits
      when (oacq_ctr.inc()) { sending_put := Bool(false) }
    }

    val ognt_block = ognt.hasMultibeatData()
    val gnt_data_buffer = Reg(Vec(factor, UInt(width = outerDataBits)))
    val gnt_client_id = Reg(ognt.client_xact_id)
    val gnt_manager_id = Reg(ognt.manager_xact_id)

    val ignt_ctr = Counter(innerDataBeats)
    val ognt_ctr = Counter(factor)
    val sending_get = Reg(init = Bool(false))

    val get_block_grant = Grant(
      is_builtin_type = Bool(true),
      g_type = Grant.getDataBlockType,
      client_xact_id = gnt_client_id,
      manager_xact_id = gnt_manager_id,
      addr_beat = ignt_ctr.value,
      data = gnt_data_buffer.toBits)(innerConfig)

    val smallget_grant = ognt.g_type === Grant.getDataBeatType
    val get_grant_shift = Cat(smallget_roq.io.deq.data,
                              UInt(0, outerByteAddrBits + 3))

    smallget_roq.io.deq.valid := io.out.grant.fire() && smallget_grant
    smallget_roq.io.deq.tag := ognt.client_xact_id

    val get_grant = Grant(
      is_builtin_type = Bool(true),
      g_type = Grant.getDataBeatType,
      client_xact_id = ognt.client_xact_id,
      manager_xact_id = ognt.manager_xact_id,
      addr_beat = ognt.addr_beat >> UInt(log2Up(factor)),
      data = ognt.data << get_grant_shift)(innerConfig)

    io.in.grant.valid := sending_get || (io.out.grant.valid && !ognt_block)
    io.out.grant.ready := !sending_get && (ognt_block || io.in.grant.ready)

    io.in.grant.bits := MuxBundle(Wire(io.in.grant.bits, init=ognt), Seq(
      sending_get -> get_block_grant,
      smallget_grant -> get_grant))

    when (io.out.grant.valid && ognt_block && !sending_get) {
      gnt_data_buffer(ognt_ctr.value) := ognt.data
      when (ognt_ctr.inc()) {
        gnt_client_id := ognt.client_xact_id
        gnt_manager_id := ognt.manager_xact_id
        sending_get := Bool(true)
      }
    }

    when (io.in.grant.ready && sending_get) {
      ignt_ctr.inc()
      sending_get := Bool(false)
    }
  } else { io.out <> io.in }
}
