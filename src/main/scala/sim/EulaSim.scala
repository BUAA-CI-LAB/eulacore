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

object DeviceSpace extends HasEulaCoreParameter {
  // (start, size)
  def device = List(
    (Settings.getLong("ConfregBase1"), Settings.getLong("ConfregSize")), // confreg
    (Settings.getLong("ConfregBase2"), Settings.getLong("ConfregSize")),
    (Settings.getLong("UartBase"), Settings.getLong("UartSize")),
  )

  def isDevice(addr: UInt) = device.map(range => {
    require(isPow2(range._2))
    val bits = log2Up(range._2)
    (addr ^ range._1.U)(PAddrBits-1, bits) === 0.U
  }).reduce(_ || _)
}