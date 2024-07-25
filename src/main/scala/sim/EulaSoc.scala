package sim

import eulacore.EulaCoreConfig
import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import bus.axi4._
import device.{AXI4Confreg, AXI4RAM, AXI4UART8250}
import eulacore._
import _root_.utils.GTimer
import bus.simplebus._
import difftest._
import top.Settings
import top.{Top, Core}

class EulaSoC extends Module {
  override val desiredName = "SimTop"

  val io = IO(new Bundle{
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
    val timer = Output(UInt(64.W))
  })

  // axi4 master
  val core = Module(new Core())

    // // axi4 slave
  // mem
  val mem = Module(new AXI4RAM(memByte = (Settings.getLong("RAMSize") - Settings.getLong("RAMBase")).toInt, useBlackBox = true))
  val memdelay = Module(new AXI4Delayer(0))
  // confreg
  val confreg = Module(new AXI4Confreg)
  // uart 8250
  val uart8250 = Module(new AXI4UART8250)

  val addrSpace = List(
    (Settings.getLong("ConfregBase1"), Settings.getLong("ConfregSize")), // confreg
    (Settings.getLong("ConfregBase2"), Settings.getLong("ConfregSize")),
    (Settings.getLong("UartBase"), Settings.getLong("UartSize")),
    (Settings.getLong("RAMBase"), Settings.getLong("RAMSize")),
  ) 

  val xbar1ton = Module(new AXI4XBar1toN(addrSpace))
  val xbarnto1 = Module(new AXI4XBarNto1(2))
  xbar1ton.io.in <> core.io.out
  xbar1ton.io.out(0) <> xbarnto1.io.in(0)
  xbar1ton.io.out(1) <> xbarnto1.io.in(1)
  xbar1ton.io.out(2) <> uart8250.io.in
  xbar1ton.io.out(3) <> memdelay.io.in

  uart8250.io.extra.get.in.ch := 0.U
  mem.io.in <> memdelay.io.out
  confreg.io.in <> xbarnto1.io.out

  core.io.clock := clock
  core.io.reset := reset
  core.io.ext_int := 0.U


  val log_begin, log_end, log_level = WireInit(0.U(64.W))
  log_begin := io.logCtrl.log_begin
  log_end := io.logCtrl.log_end
  log_level := io.logCtrl.log_level

  io.uart.in.valid := 0.U
  io.uart.out.valid := uart8250.io.extra.get.out.valid
  io.uart.out.ch := uart8250.io.extra.get.out.ch
  
  io.timer := GTimer()
  assert(log_begin <= log_end)
  BoringUtils.addSource((GTimer() >= log_begin) && (GTimer() < log_end), "DISPLAY_ENABLE")
  val dummyWire = WireInit(false.B)
  BoringUtils.addSink(dummyWire, "DISPLAY_ENABLE")
}