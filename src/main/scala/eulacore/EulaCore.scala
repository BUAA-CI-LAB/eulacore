package eulacore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import bus.simplebus._
import bus.axi4._
import eulacore.mem.{La32rCache, La32rCacheConfig, La32rMMU, La32rMMUConfig, La32rTLBExcpIO}
import utils._
import top.Settings
import eulacore.backend.Backend_inorder

trait HasEulaCoreParameter {
  val XLEN = 32
  val HasDiv = true
  val HasIcache = Settings.get("HasIcache")
  val HasDcache = Settings.get("HasDcache")
  val HasIMMU = Settings.get("HasIMMU")
  val HasDMMU = Settings.get("HasDMMU")

  // be careful bundle width !!!
  class ImmuUserBundle extends Bundle {
    val pc = Output(UInt(XLEN.W))
    val npc = Output(UInt(XLEN.W))
    val brIdx =  Output(UInt(4.W))
    val memAccessMaster = Output(UInt(2.W))
    val tlbExcp = new La32rTLBExcpIO
    val mat = Output(UInt(2.W))
  }
  class DmmuUserBundle extends Bundle {
    val isDeviceLoad = Output(UInt(1.W))
    val memAccessMaster = Output(UInt(2.W))
    val tlbExcp = new La32rTLBExcpIO
    val paddr = Output(UInt(32.W))
    val isInvalidPaddr = Output(Bool())
    val mat = Output(UInt(2.W))
  }
  val immuUserBits = (new ImmuUserBundle).getWidth
  val dmmuUserBits = (new DmmuUserBundle).getWidth
  val AddrBits = 32 // AddrBits is used in some cases
  val VAddrBits = 32 // VAddrBits is Virtual Memory addr bits
  val PAddrBits = 32 // PAddrBits is Phyical Memory addr bits
  val DataBits = XLEN
  val DataBytes = DataBits / 8
  val EnablePerfCnt = true
}

trait HasEulaCoreConst extends HasEulaCoreParameter {
  val IndependentBru = false
}

trait HasEulaCoreLog { this: RawModule =>
  implicit val moduleName: String = this.name
}

abstract class EulaCoreModule extends Module with HasEulaCoreParameter with HasEulaCoreConst with HasEulaCoreLog with HasLa32rExceptionNO
abstract class EulaCoreBundle extends Bundle with HasEulaCoreParameter with HasEulaCoreConst

case class EulaCoreConfig(
                           FPGAPlatform: Boolean = true,
                           EnableDebug: Boolean = Settings.get("EnableDebug"),
                           EnhancedLog: Boolean = true
                         )


class EulaCore(implicit val p: EulaCoreConfig) extends EulaCoreModule {
  val io = IO(new Bundle() {
    val cachedMem = new SimpleBusUC(addrBits = PAddrBits)
    val uncachedMem = new SimpleBusUC(addrBits = PAddrBits)
    val ipi = Input(Bool()) // inter-core interrupt for la32r
    val hwi = Input(UInt(8.W)) // hardware interrupt for la32r
  })

  println("dmmuUserBits=", dmmuUserBits)
  println("immuUserBits=", immuUserBits)

  val frontend = Module(new Frontend_embedded)

  val backend = Module(new Backend_inorder)

  PipelineVector2Connect(new DecodeIO, frontend.io.out(0), frontend.io.out(1), backend.io.in(0), backend.io.in(1), frontend.io.flushVec(1), 4)

  val cachedXbar = Module(new SimpleBusCrossbarNto1(2))
  val uncachedXbar = Module(new SimpleBusCrossbarNto1(2))

  val immu = La32rMMU(in = frontend.io.imem, enable = HasIMMU)(La32rMMUConfig(name = "immu", userBits = immuUserBits, tlbEntryNum = Settings.getInt("TlbEntryNum"), FPGAPlatform = p.FPGAPlatform))

  val icache = La32rCache(in = immu.io.out, mmio = uncachedXbar.io.in(1), flush = Fill(2, frontend.io.flushVec(0) | frontend.io.bpFlush), enable = HasIcache)(La32rCacheConfig(ro = true, name = "icache", userBits = immuUserBits, totalSize = Settings.getInt("IcacheSize")))

  val dmmu = La32rMMU(in = backend.io.dmem, enable = HasDMMU)(La32rMMUConfig(name = "dmmu", userBits = dmmuUserBits, tlbEntryNum = Settings.getInt("TlbEntryNum"), FPGAPlatform = p.FPGAPlatform))

  val dcache = La32rCache(in = dmmu.io.out, mmio = uncachedXbar.io.in(0), flush = "b00".U, enable = HasDcache)(La32rCacheConfig(ro = false, name = "dcache", userBits = dmmuUserBits, totalSize = Settings.getInt("DcacheSize")))

  cachedXbar.io.in(0) <> dcache.io.out.mem
  cachedXbar.io.in(1) <> icache.io.out.mem

  io.cachedMem <> cachedXbar.io.out
  io.uncachedMem <> uncachedXbar.io.out

  icache.io.out.coh <> DontCare
  dcache.io.out.coh <> DontCare

  // redirect
  frontend.io.redirect <> backend.io.redirect
  backend.io.flush := frontend.io.flushVec(3, 2)


  BoringUtils.addSource(io.ipi, "ipi")
  BoringUtils.addSource(io.hwi, "hwi")

  // priority : dcache > dcacheInvalidUnit > icache
  val s_idle :: s_icache :: s_dcache :: s_dcacheInvUnit :: Nil = Enum(4)
  val cacheSemaphore = RegInit(s_idle)
  val icacheTryGetSem = WireInit(false.B)
  val dcacheTryGetSem = WireInit(false.B)
  val dcacheInvUnitTryGetSem = WireInit(false.B)
  val sendICacheSem = WireInit(false.B)
  val sendDCacheSem = WireInit(false.B)
  val sendDcacheInvUnitSem = WireInit(false.B)
  val icacheReleaseSem = WireInit(false.B)
  val dcacheReleaseSem = WireInit(false.B)
  val dcacheInvUnitReleaseSem = WireInit(false.B)
  BoringUtils.addSink(icacheTryGetSem, "icacheTryGetSem")
  BoringUtils.addSink(dcacheTryGetSem, "dcacheTryGetSem")
  BoringUtils.addSink(dcacheInvUnitTryGetSem, "dcacheInvUnitTryGetSem")
  BoringUtils.addSource(sendICacheSem, "sendICacheSem")
  BoringUtils.addSource(sendDCacheSem, "sendDCacheSem")
  BoringUtils.addSource(sendDcacheInvUnitSem,"sendDcacheInvUnitSem")
  BoringUtils.addSink(icacheReleaseSem, "icacheReleaseSem")
  BoringUtils.addSink(dcacheReleaseSem, "dcacheReleaseSem")
  BoringUtils.addSink(dcacheInvUnitReleaseSem, "dcacheInvUnitReleaseSem")

  sendICacheSem := (cacheSemaphore === s_idle) && icacheTryGetSem && !dcacheTryGetSem && !dcacheInvUnitTryGetSem
  sendDcacheInvUnitSem := (cacheSemaphore === s_idle) && dcacheInvUnitTryGetSem && !dcacheTryGetSem
  sendDCacheSem := (cacheSemaphore === s_idle) && dcacheTryGetSem
  switch (cacheSemaphore) {
    is (s_idle) {
      when (sendICacheSem) {
        cacheSemaphore := s_icache
      }
      when (sendDCacheSem) {
        cacheSemaphore := s_dcache
      }
      when (sendDcacheInvUnitSem) {
        cacheSemaphore := s_dcacheInvUnit
      }
    }
    is (s_icache) {
      when (icacheReleaseSem) { cacheSemaphore := s_idle }
    }
    is (s_dcache) {
      when (dcacheReleaseSem) { cacheSemaphore := s_idle }
    }
    is (s_dcacheInvUnit) {
      when (dcacheInvUnitReleaseSem) { cacheSemaphore := s_idle }
    }
  }

}
