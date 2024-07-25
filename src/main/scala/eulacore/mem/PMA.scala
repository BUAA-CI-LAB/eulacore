package eulacore.mem

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import bus.simplebus._
import bus.axi4._
import chisel3.experimental.IO
import eulacore.HasEulaCoreParameter
import utils._
import top.Settings
import sim.DeviceSpace

object PMA extends HasEulaCoreParameter {
  // (start, size)
  def addrSpace = List(
    (Settings.getLong("RAMBase"), Settings.getLong("RAMSize")),
  ) ++ DeviceSpace.device

  def isValidAddr(addr: UInt) = addrSpace.map(range => {
    addr >= range._1.U && addr < (range._1 + range._2).U
  }).reduce(_ || _)

}
