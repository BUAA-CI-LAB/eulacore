

package utils

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utils.LogLevel.LogLevel

import eulacore.EulaCoreConfig

object LogLevel  {
  type LogLevel = UInt
  val LADEBUG = 1.U(64.W)
  val DEBUG = 2.U(64.W)
  val INFO  = 4.U(64.W)
  val WARN  = 8.U(64.W)
  val ERROR = 16.U(64.W)
  val OFF   = 0.U(64.W)
}

object LogUtil {

  def displayLog: Bool = {
    val enableDisplay = WireInit(false.B)
    BoringUtils.addSink(enableDisplay, "DISPLAY_ENABLE")
    enableDisplay
  }

   def getLogLevel: UInt = {
     val log_level = WireInit(0.U(64.W))
     BoringUtils.addSink(log_level, "DISPLAY_LOG_LEVEL")
     log_level
   }

  def apply(debugLevel: LogLevel)
           (prefix: Boolean, cond: Bool, pable: Printable)
           (implicit name: String): Any = {
    val commonInfo = p"[${GTimer()}] $name: "
    when (cond && displayLog && (getLogLevel & debugLevel).orR) {
      if(prefix) printf(commonInfo)
      printf(pable)
    }
  }
}

sealed abstract class LogHelper(val logLevel: LogLevel) {

  def apply(cond: Bool, fmt: String, data: Bits*)(implicit name: String): Any =
    apply(cond, Printable.pack(fmt, data:_*))
  def apply(cond: Bool, pable: Printable)(implicit name: String): Any = apply(true, cond, pable)
  def apply(fmt: String, data: Bits*)(implicit name: String): Any =
    apply(true.B, Printable.pack(fmt, data:_*))
  def apply(pable: Printable)(implicit name: String): Any = apply(true.B, pable)
  def apply(prefix: Boolean, fmt: String, data: Bits*)(implicit name: String): Any = apply(prefix, true.B, Printable.pack(fmt, data:_*))
  def apply(prefix: Boolean, pable: Printable)(implicit name: String): Any = apply(prefix, true.B, pable)
  def apply(prefix: Boolean, cond: Bool, fmt: String, data: Bits*)(implicit name: String): Any =
    apply(prefix, cond, Printable.pack(fmt, data:_*))
  def apply(prefix: Boolean, cond: Bool, pable: Printable)(implicit name: String): Any =
    LogUtil(logLevel)(prefix, cond, pable)


  def apply(flag: Boolean = EulaCoreConfig().EnableDebug, cond: Bool = true.B)(body: => Unit): Any = {
    if(EulaCoreConfig().EnhancedLog){
      if(flag) { when (cond && LogUtil.displayLog) { body } }
    } else {
      if(flag) { when (cond) { body } }
    }
  }
}
object LADebug extends LogHelper(LogLevel.LADEBUG)
object Debug extends LogHelper(LogLevel.DEBUG)
object Info extends LogHelper(LogLevel.INFO)
object Warn extends LogHelper(LogLevel.WARN)
object Error extends LogHelper(LogLevel.ERROR)

object ShowType {
  def apply[T: Manifest](t: T) = println(manifest[T])
}
