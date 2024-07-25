package top
import chisel3._
import chisel3.util._
import bus.axi4._
import chisel3.util.experimental.{BoringUtils, forceName}

class Core extends BlackBox {
  override val desiredName = "mycpu_mega_top"
  val io = IO(new Bundle {
    val out = new AXI4
    val ext_int = Input(UInt(8.W))
    val clock = Input(Clock())
    val reset = Input(Bool())
    val global_reset = Output(Bool())

    val debug_wb_pc = Output(UInt(32.W))
    val debug_wb_rf_wen = Output(UInt(4.W))
    val debug_wb_rf_wnum = Output(UInt(5.W))
    val debug_wb_rf_wdata = Output(UInt(32.W))
    val debug_wb_instr = Output(UInt(32.W))

    val aclk = Input(Clock())   // nothing to do when sim
    val aresetn = Input(Bool()) // nothing to do when sim

  }).suggestName("io")

  forceName(io.out.ar.bits.id, "arid")
  forceName(io.out.ar.bits.addr, "araddr")
  forceName(io.out.ar.bits.len, "arlen")
  forceName(io.out.ar.bits.size, "arsize")
  forceName(io.out.ar.bits.burst, "arburst")
  forceName(io.out.ar.bits.lock, "arlock")
  forceName(io.out.ar.bits.cache, "arcache")
  forceName(io.out.ar.bits.prot, "arprot")
  forceName(io.out.ar.bits.qos, "arqos")
  forceName(io.out.ar.bits.user, "aruser")
  forceName(io.out.ar.valid, "arvalid")
  forceName(io.out.ar.ready, "arready")

  forceName(io.out.r.bits.id, "rid")
  forceName(io.out.r.bits.data, "rdata")
  forceName(io.out.r.bits.resp, "rresp")
  forceName(io.out.r.bits.last, "rlast")
  forceName(io.out.r.bits.user, "ruser")
  forceName(io.out.r.valid, "rvalid")
  forceName(io.out.r.ready, "rready")

  forceName(io.out.aw.bits.id, "awid")
  forceName(io.out.aw.bits.addr, "awaddr")
  forceName(io.out.aw.bits.len, "awlen")
  forceName(io.out.aw.bits.size, "awsize")
  forceName(io.out.aw.bits.burst, "awburst")
  forceName(io.out.aw.bits.lock, "awlock")
  forceName(io.out.aw.bits.cache, "awcache")
  forceName(io.out.aw.bits.prot, "awprot")
  forceName(io.out.aw.bits.qos, "awqos")
  forceName(io.out.aw.bits.user, "awuser")
  forceName(io.out.aw.valid, "awvalid")
  forceName(io.out.aw.ready, "awready")

  // forceName(io.out.w.bits.id, "wid")
  forceName(io.out.w.bits.data, "wdata")
  forceName(io.out.w.bits.strb, "wstrb")
  forceName(io.out.w.bits.last, "wlast")
  forceName(io.out.w.valid, "wvalid")
  forceName(io.out.w.ready, "wready")

  forceName(io.out.b.bits.id, "bid")
  forceName(io.out.b.bits.resp, "bresp")
  forceName(io.out.b.bits.user, "buser")
  forceName(io.out.b.valid, "bvalid")
  forceName(io.out.b.ready, "bready")
}