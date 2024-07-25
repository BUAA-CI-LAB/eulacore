package top


import eulacore.{EulaCore, EulaCoreConfig}
import chisel3._
import chisel3.stage._
import bus.simplebus._
import bus.axi4._
import chisel3.util.experimental.{BoringUtils, forceName}


class ChiplabTop extends RawModule {
  override val desiredName = "core_top"
  val io = IO(new Bundle() {
    val intrpt = Input(UInt(8.W))
    val clock = Input(Clock())
    val reset = Input(Bool())

    val arid = Output(UInt(4.W))
    val araddr = Output(UInt(32.W))
    val arlen = Output(UInt(4.W)) // TODO : 4 OR 8 ?
    val arsize = Output(UInt(3.W))
    val arburst = Output(UInt(2.W))
    val arlock = Output(UInt(2.W))
    val arcache = Output(UInt(4.W))
    val arprot = Output(UInt(3.W))
    val arvalid = Output(Bool())
    val arready = Input(Bool())

    val rid = Input(UInt(4.W))
    val rdata = Input(UInt(32.W))
    val rresp = Input(UInt(2.W))
    val rlast = Input(Bool())
    val rvalid = Input(Bool())
    val rready = Output(Bool())

    val awid = Output(UInt(4.W))
    val awaddr = Output(UInt(32.W))
    val awlen = Output(UInt(4.W)) // TODO : 4 OR 8 ?
    val awsize = Output(UInt(3.W))
    val awburst = Output(UInt(2.W))
    val awlock = Output(UInt(2.W))
    val awcache = Output(UInt(4.W))
    val awprot = Output(UInt(3.W))
    val awvalid = Output(Bool())
    val awready = Input(Bool())

    val wid = Output(UInt(4.W))
    val wdata = Output(UInt(32.W))
    val wstrb = Output(UInt(4.W))
    val wlast = Output(Bool())
    val wvalid = Output(Bool())
    val wready = Input(Bool())

    val bid = Input(UInt(4.W))
    val bresp = Input(UInt(2.W))
    val bvalid = Input(Bool())
    val bready = Output(Bool())

    val break_point = Input(Bool())
    val infor_flag = Input(Bool())
    val reg_num = Input(UInt(5.W))
    val ws_valid = Output(Bool())
    val rf_rdata = Output(UInt(32.W))
  })

  withClockAndReset(io.clock, !io.reset) {
    val memXbar = Module(new SimpleBusCrossbarNto1(2))
    val core = Module(new EulaCore()(EulaCoreConfig()))

    core.io.ipi := false.B
    core.io.hwi := io.intrpt

    memXbar.io.in(0) <> core.io.uncachedMem
    memXbar.io.in(1) <> core.io.cachedMem
    val axi4mem = SimpleBus2AXI4Converter(in = memXbar.io.out, outType = new AXI4, isFromCache = true)

    io.arid := axi4mem.ar.bits.id
    io.araddr := axi4mem.ar.bits.addr
    io.arlen := axi4mem.ar.bits.len
    io.arsize := axi4mem.ar.bits.size
    io.arburst := axi4mem.ar.bits.burst
    io.arlock := axi4mem.ar.bits.lock
    io.arcache := axi4mem.ar.bits.cache
    io.arprot := axi4mem.ar.bits.prot
    io.arvalid := axi4mem.ar.valid
    axi4mem.ar.ready := io.arready

    axi4mem.r.bits.id := io.rid
    axi4mem.r.bits.data := io.rdata
    axi4mem.r.bits.resp := io.rresp
    axi4mem.r.bits.last := io.rlast
    axi4mem.r.valid := io.rvalid
    io.rready := axi4mem.r.ready

    io.awid := axi4mem.aw.bits.id
    io.awaddr := axi4mem.aw.bits.addr
    io.awlen := axi4mem.aw.bits.len
    io.awsize := axi4mem.aw.bits.size
    io.awburst := axi4mem.aw.bits.burst
    io.awlock := axi4mem.aw.bits.lock
    io.awcache := axi4mem.aw.bits.cache
    io.awprot := axi4mem.aw.bits.prot
    io.awvalid := axi4mem.aw.valid
    axi4mem.aw.ready := io.awready

    io.wid := 0.U
    io.wdata := axi4mem.w.bits.data
    io.wstrb := axi4mem.w.bits.strb
    io.wlast := axi4mem.w.bits.last
    io.wvalid := axi4mem.w.valid
    axi4mem.w.ready := io.wready

    axi4mem.b.bits.id := io.bid
    axi4mem.b.bits.resp := io.bresp
    axi4mem.b.valid := io.bvalid
    io.bready := axi4mem.b.ready

    axi4mem.r.bits.user := 0.U
    axi4mem.b.bits.user := 0.U

    io.ws_valid := false.B
    io.rf_rdata := 0.U

  }
  forceName(io.intrpt, "intrpt")
  forceName(io.clock, "aclk")
  forceName(io.reset, "aresetn")

  forceName(io.arid, "arid")
  forceName(io.araddr, "araddr")
  forceName(io.arlen, "arlen")
  forceName(io.arsize, "arsize")
  forceName(io.arburst, "arburst")
  forceName(io.arlock, "arlock")
  forceName(io.arcache, "arcache")
  forceName(io.arprot, "arprot")
  forceName(io.arvalid, "arvalid")
  forceName(io.arready, "arready")

  forceName(io.rid, "rid")
  forceName(io.rdata, "rdata")
  forceName(io.rresp, "rresp")
  forceName(io.rlast, "rlast")
  forceName(io.rvalid, "rvalid")
  forceName(io.rready, "rready")

  forceName(io.awid, "awid")
  forceName(io.awaddr, "awaddr")
  forceName(io.awlen, "awlen")
  forceName(io.awsize, "awsize")
  forceName(io.awburst, "awburst")
  forceName(io.awlock, "awlock")
  forceName(io.awcache, "awcache")
  forceName(io.awprot, "awprot")
  forceName(io.awvalid, "awvalid")
  forceName(io.awready, "awready")

  forceName(io.wid, "wid")
  forceName(io.wdata, "wdata")
  forceName(io.wstrb, "wstrb")
  forceName(io.wlast, "wlast")
  forceName(io.wvalid, "wvalid")
  forceName(io.wready, "wready")

  forceName(io.bid, "bid")
  forceName(io.bresp, "bresp")
  forceName(io.bvalid, "bvalid")
  forceName(io.bready, "bready")

  forceName(io.break_point, "break_point")
  forceName(io.infor_flag, "infor_flag")
  forceName(io.reg_num, "reg_num")
  forceName(io.ws_valid, "ws_valid")
  forceName(io.rf_rdata, "rf_rdata")

}