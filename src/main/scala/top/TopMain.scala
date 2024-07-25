package top

import eulacore.{EulaCore, EulaCoreConfig}
import sim.{EulaSoC}
import chisel3._
import chisel3.stage._
import bus.simplebus._
import bus.axi4._
import chisel3.util.experimental.{BoringUtils, forceName}


class Top(implicit val p: EulaCoreConfig) extends Module {
  override val desiredName = "mycpu_mega_top"
  val io = IO(new Bundle() {
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

  })

  if(!p.FPGAPlatform) {
    val memXbar = Module(new SimpleBusCrossbarNto1(2))
    val core = Module(new EulaCore()(p))

    core.io.ipi := false.B
    core.io.hwi := io.ext_int

    memXbar.io.in(0) <> core.io.uncachedMem
    memXbar.io.in(1) <> core.io.cachedMem
    val axi4mem = SimpleBus2AXI4Converter(in = memXbar.io.out, outType = new AXI4, isFromCache = true)
        io.out <> axi4mem

    axi4mem.r.bits.user := 0.U
    axi4mem.b.bits.user := 0.U

    val w_debug_wb_rf_wen = WireInit(0.U(4.W))
    val w_debug_wb_pc = WireInit(0.U(32.W))
    val w_debug_wb_rf_wdata = WireInit(0.U(32.W))
    val w_debug_wb_rf_wnum = WireInit(0.U(5.W))
    val w_debug_wb_instr = WireInit(0.U(32.W))
    BoringUtils.addSink(w_debug_wb_rf_wen, "DEBUG_WB_RF_WEN")
    BoringUtils.addSink(w_debug_wb_pc, "DEBUG_WB_PC")
    BoringUtils.addSink(w_debug_wb_rf_wdata, "DEBUG_WB_RF_WDATA")
    BoringUtils.addSink(w_debug_wb_rf_wnum, "DEBUG_WB_RF_WNUM")
    BoringUtils.addSink(w_debug_wb_instr, "DEBUG_WB_INSTR")

    io.debug_wb_rf_wen := w_debug_wb_rf_wen
    io.debug_wb_pc := w_debug_wb_pc
    io.debug_wb_rf_wdata := w_debug_wb_rf_wdata
    io.debug_wb_rf_wnum := w_debug_wb_rf_wnum
    io.debug_wb_instr := w_debug_wb_instr

    io.global_reset := !io.reset
  } else {
    withClockAndReset(io.clock, !io.reset) {
      val memXbar = Module(new SimpleBusCrossbarNto1(2))
      val core = Module(new EulaCore()(p))

      core.io.ipi := false.B
      core.io.hwi := io.ext_int

      memXbar.io.in(0) <> core.io.uncachedMem
      memXbar.io.in(1) <> core.io.cachedMem
      val axi4mem = SimpleBus2AXI4Converter(in = memXbar.io.out, outType = new AXI4, isFromCache = true)
          io.out <> axi4mem

      axi4mem.r.bits.user := 0.U
      axi4mem.b.bits.user := 0.U

      val w_debug_wb_rf_wen = WireInit(0.U(4.W))
      val w_debug_wb_pc = WireInit(0.U(32.W))
      val w_debug_wb_rf_wdata = WireInit(0.U(32.W))
      val w_debug_wb_rf_wnum = WireInit(0.U(5.W))
      val w_debug_wb_instr = WireInit(0.U(32.W))
      BoringUtils.addSink(w_debug_wb_rf_wen, "DEBUG_WB_RF_WEN")
      BoringUtils.addSink(w_debug_wb_pc, "DEBUG_WB_PC")
      BoringUtils.addSink(w_debug_wb_rf_wdata, "DEBUG_WB_RF_WDATA")
      BoringUtils.addSink(w_debug_wb_rf_wnum, "DEBUG_WB_RF_WNUM")
      BoringUtils.addSink(w_debug_wb_instr, "DEBUG_WB_INSTR")

      io.debug_wb_rf_wen := w_debug_wb_rf_wen
      io.debug_wb_pc := w_debug_wb_pc
      io.debug_wb_rf_wdata := w_debug_wb_rf_wdata
      io.debug_wb_rf_wnum := w_debug_wb_rf_wnum
      io.debug_wb_instr := w_debug_wb_instr


      io.global_reset := !io.reset
    }
  }
  forceName(io.ext_int, "ext_int")
  forceName(io.clock, "aclk")
  forceName(io.reset, "aresetn")
  forceName(io.global_reset, "global_reset")

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

  forceName(io.debug_wb_pc, "debug_wb_pc")
  forceName(io.debug_wb_rf_wen, "debug_wb_rf_wen")
  forceName(io.debug_wb_rf_wnum, "debug_wb_rf_wnum")
  forceName(io.debug_wb_rf_wdata, "debug_wb_rf_wdata")
  forceName(io.debug_wb_instr, "debug_wb_instr")
}

object TopMain extends App {
  def parseArgs(info: String, args: Array[String]): String = {
    var target = ""
    for (arg <- args) { if (arg.startsWith(info + "=") == true) { target = arg } }
    require(target != "")
    target.substring(info.length()+1)
  }
  val core = parseArgs("CORE", args)
  val soc = parseArgs("SOC", args)
  val prod = parseArgs("PROD", args)
  
  val s = (soc match {
    case "eulasoc" => Nil
    case "perftest" => Nil
    case "megasoc" => Nil
  } ) ++ ( core match {
    case "inorder" => Nil
  } )
  s.foreach{Settings.settings += _} // add and overwrite DefaultSettings
  println("====== Settings = (" + soc + ", " +  core + ") ======")
  Settings.settings.toList.sortBy(_._1)(Ordering.String).foreach {
    case (f, v: Long) =>
      println(f + " = 0x" + v.toHexString)
    case (f, v) =>
      println(f + " = " + v)
  }
  if (soc == "eulasoc" && prod == "top") {
    (new ChiselStage).execute(args, Seq(
      // cpu as blackbox
      ChiselGeneratorAnnotation(() => new EulaSoC))
    )
  } else if ((soc == "perftest" || soc == "megasoc") && prod == "cpu") {
    (new ChiselStage).execute(args, Seq(
      // example cpu
      ChiselGeneratorAnnotation(() => new Top()(EulaCoreConfig(FPGAPlatform = true))))
    )
  } else if(soc == "eulasoc" && prod == "cpu") {
    (new ChiselStage).execute(args, Seq(
      // example cpu
      ChiselGeneratorAnnotation(() => new Top()(EulaCoreConfig(FPGAPlatform = false))))
    )
  } else {
    println("unimplement")
    assert(false)
  }
}
