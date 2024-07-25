package eulacore.mem

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import bus.simplebus._
import bus.axi4._
import chisel3.experimental.IO
import eulacore.{HasEulaCoreParameter, HasLa32rCSRConst}
import sim.DeviceSpace
import utils._
import top.Settings
import eulacore._

case class La32rMMUConfig (
  name: String = "mmu",
  userBits: Int = 0,
  tlbEntryNum: Int = 32,
  FPGAPlatform : Boolean
)

trait HasLa32rMMUConst {
  implicit val la32rMMUConfig: La32rMMUConfig

  val userBits = la32rMMUConfig.userBits
  val mmuname = la32rMMUConfig.name
  val tlbEntryNum = la32rMMUConfig.tlbEntryNum

}

trait HasLa32rMMUIO extends HasEulaCoreParameter with HasLa32rMMUConst {
  class La32rMMUIO extends Bundle {
    val in = Flipped(new SimpleBusUC(userBits = userBits, addrBits = VAddrBits))
    val out = new SimpleBusUC(userBits = userBits, addrBits = PAddrBits)
  }
  val io = IO(new La32rMMUIO)
}


class La32rMMU(implicit val la32rMMUConfig: La32rMMUConfig) extends EulaCoreModule with HasLa32rMMUIO with HasLa32rCSRConst {
  assert(mmuname == "immu" || mmuname == "dmmu" || mmuname == "csr")
  val isIMMU = mmuname == "immu" // otherwise is dmmu

  val CRMD = WireInit(0.U(32.W))
  val DMW0 = WireInit(0.U(32.W))
  val DMW1 = WireInit(0.U(32.W))

  BoringUtils.addSink(CRMD, "CRMD")
  BoringUtils.addSink(DMW0, "DMW0")
  BoringUtils.addSink(DMW1, "DMW1")

  val crmdStruct = CRMD.asTypeOf(new CRMDStruct)
  val dmw0Struct = DMW0.asTypeOf(new DMWStruct)
  val dmw1Struct = DMW1.asTypeOf(new DMWStruct)

  io.out <> io.in

  val isDAT = crmdStruct.DA === 1.U && crmdStruct.PG === 0.U // is Direct Address Translation
  val DATPaddr = io.in.req.bits.addr(PAddrBits - 1, 0)
  val DATMAT = if (isIMMU) crmdStruct.DATF else crmdStruct.DATM // Direct Address Translation's Memory Access Type

  val PLVoh = UIntToOH(crmdStruct.PLV)
  val DMWVec = Seq(dmw0Struct, dmw1Struct)
  val DMWHitVec = DMWVec.map(dmw => io.in.req.bits.addr(31, 29) === dmw.VSEG && (PLVoh & Cat(dmw.PLV3, dmw.pad2, dmw.PLV0)).orR)
  val isDMAT = DMWHitVec.reduce(_||_) // is Direct Mapped Address Translation

  val DMATPaddr = Mux(DMWHitVec(0), Cat(DMWVec(0).PSEG, io.in.req.bits.addr(28, 0))(PAddrBits - 1, 0),Cat(DMWVec(1).PSEG, io.in.req.bits.addr(28, 0))(PAddrBits - 1, 0))
  val DMATMAT = Mux(DMWHitVec(0), DMWVec(0).MAT, DMWVec(1).MAT)


  val isTLB = !isDAT && !isDMAT

  val tlb = Module(new La32rTLB)

  tlb.io.in.valid := io.in.req.valid && isTLB
  tlb.io.in.bits.vaddr := io.in.req.bits.addr
  if (mmuname == "immu") {
    tlb.io.in.bits.memAccessMaster := io.in.req.bits.user.get.asTypeOf(new ImmuUserBundle).memAccessMaster
  } else {
    tlb.io.in.bits.memAccessMaster := io.in.req.bits.user.get.asTypeOf(new DmmuUserBundle).memAccessMaster
  }

  val tlbExcp = (tlb.io.out.bits.excp.asUInt() & Fill(tlb.io.out.bits.excp.asUInt().getWidth, isTLB)).asTypeOf(tlb.io.out.bits.excp)
  val TLBPaddr = tlb.io.out.bits.paddr
  val TLBMAT = tlb.io.out.bits.mat


  io.out.req.bits.addr := Mux(isDAT, DATPaddr,Mux(isDMAT, DMATPaddr, TLBPaddr))
  val memoryAccessType = Mux(isDAT, DATMAT,Mux(isDMAT, DMATMAT, TLBMAT))

  if (mmuname == "immu") {
    val immuUserBits = Wire(new ImmuUserBundle)
    immuUserBits := io.in.req.bits.user.get.asTypeOf(new ImmuUserBundle)
    immuUserBits.tlbExcp := tlbExcp
    immuUserBits.mat := memoryAccessType
    io.out.req.bits.user.map(_ := immuUserBits.asUInt())
  } else {
    val dmmuUserBits = Wire(new DmmuUserBundle)
    dmmuUserBits := io.in.req.bits.user.get.asTypeOf(new DmmuUserBundle)
    dmmuUserBits.tlbExcp := tlbExcp
    dmmuUserBits.isDeviceLoad := io.in.req.bits.cmd === SimpleBusCmd.read && DeviceSpace.isDevice(io.out.req.bits.addr)
    dmmuUserBits.paddr := io.out.req.bits.addr
    dmmuUserBits.isInvalidPaddr := !PMA.isValidAddr(io.out.req.bits.addr)
    dmmuUserBits.mat := memoryAccessType
    io.out.req.bits.user.map(_ := dmmuUserBits.asUInt())
  }


}

class La32rMMU_fake(implicit val la32rMMUConfig: La32rMMUConfig) extends EulaCoreModule with HasLa32rMMUIO {
  val CRMD = WireInit(0.U(32.W))
  val DMW0 = WireInit(0.U(32.W))
  val DMW1 = WireInit(0.U(32.W))
  val ASID = WireInit(0.U(32.W))

  BoringUtils.addSink(CRMD, "CRMD")
  BoringUtils.addSink(DMW0, "DMW0")
  BoringUtils.addSink(DMW1, "DMW1")
  BoringUtils.addSink(ASID, "ASID")

  io.out <> io.in
}

object La32rMMU {
  def apply(in: SimpleBusUC, enable: Boolean = true)(implicit la32rMMUConfig: La32rMMUConfig) = {
    val mmu = if(enable) {
      Module(new La32rMMU())
    } else {
      Module(new La32rMMU_fake())
    }
    mmu.io.in <> in
    mmu
  }
}