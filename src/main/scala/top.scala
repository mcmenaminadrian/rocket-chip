package Top

import Chisel._
import Node._;
import Constants._;

class ioTop(htif_width: Int) extends Bundle  {
  val debug   = new ioDebug();
  val host    = new ioHost(htif_width);
  val mem     = new ioMem();
}

class Top() extends Component {

  val htif_width = 16
  val io = new ioTop(htif_width);
  val htif = new rocketHTIF(htif_width, 1)
  
  val cpu       = new rocketProc();
  val icache    = new rocketICache(128, 2); // 128 sets x 2 ways
  val icache_pf = new rocketIPrefetcher();
  val vicache   = new rocketICache(128, 2); // 128 sets x 2 ways
  val dcache    = new HellaCacheUniproc();
  val arbiter   = new rocketMemArbiter();

  arbiter.io.mem    <> io.mem; 
  arbiter.io.dcache <> dcache.io.mem;
  arbiter.io.icache <> icache_pf.io.mem;
  arbiter.io.vicache <> vicache.io.mem
  arbiter.io.htif <> htif.io.mem

  htif.io.host <> io.host
  cpu.io.host       <> htif.io.cpu(0);
  cpu.io.debug      <> io.debug;

  icache_pf.io.invalidate := cpu.io.imem.invalidate
  icache.io.mem     <> icache_pf.io.icache;
  cpu.io.imem       <> icache.io.cpu;
  cpu.io.vimem      <> vicache.io.cpu;
  cpu.io.dmem       <> dcache.io.cpu;
  
}

object top_main {
  def main(args: Array[String]) = { 
     chiselMain(args, () => new Top());
  }
}
