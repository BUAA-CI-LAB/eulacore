package eulacore.mem

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import bus.simplebus._
import bus.axi4._
import chisel3.experimental.IO
import eulacore.{HasEulaCoreLog, HasEulaCoreParameter, HasLa32rCSRConst}
import firrtl.transforms.DontTouchAnnotation
import utils._
import top.Settings
import sim.DeviceSpace


case class La32rCacheConfig (
                         ro: Boolean = false,
                         name: String = "cache",
                         userBits: Int = 0,
                         idBits: Int = 0,
                         cacheLevel: Int = 1,

                         totalSize: Int = 32, // Kbytes
                         ways: Int = 4
                       )

trait HasLa32rCacheConst {
  implicit val cacheConfig: La32rCacheConfig

  val PAddrBits: Int
  val XLEN: Int

  val cacheName = cacheConfig.name
  val userBits = cacheConfig.userBits
  val idBits = cacheConfig.idBits

  val ro = cacheConfig.ro
  val hasCoh = !ro
  val hasCohInt = (if (hasCoh) 1 else 0)
  val hasPrefetch = cacheName == "l2cache"

  val cacheLevel = cacheConfig.cacheLevel
  val TotalSize = cacheConfig.totalSize
  val Ways = cacheConfig.ways
  val LineSize = XLEN // byte
  val LineBeats = LineSize / (if (XLEN == 64) 8 else 4) // when XLEN = 32 : LineSize / 4 = 32 / 4 = 8
  val Sets = TotalSize * 1024 / LineSize / Ways
  val OffsetBits = log2Up(LineSize)
  val IndexBits = log2Up(Sets)
  val WordIndexBits = log2Up(LineBeats)
  val TagBits = PAddrBits - OffsetBits - IndexBits

  val debug = false

  def addrBundle = new Bundle {
    val tag = UInt(TagBits.W)
    val index = UInt(IndexBits.W)
    val wordIndex = UInt(WordIndexBits.W)
    val byteOffset = UInt((if (XLEN == 64) 3 else 2).W)
  }

  def CacheMetaArrayReadBus() = new SRAMReadBus(new La32rMetaBundle, set = Sets, way = Ways)
  def CacheDataArrayReadBus() = new SRAMReadBus(new La32rDataBundle, set = Sets * LineBeats, way = Ways)
  def CacheMetaArrayWriteBus() = new SRAMWriteBus(new La32rMetaBundle, set = Sets, way = Ways)
  def CacheDataArrayWriteBus() = new SRAMWriteBus(new La32rDataBundle, set = Sets * LineBeats, way = Ways)

  def getMetaIdx(addr: UInt) = addr.asTypeOf(addrBundle).index
  def getDataIdx(addr: UInt) = Cat(addr.asTypeOf(addrBundle).index, addr.asTypeOf(addrBundle).wordIndex)

  def isSameWord(a1: UInt, a2: UInt) = ((a1 >> 2) === (a2 >> 2))
  def isSetConflict(a1: UInt, a2: UInt) = (a1.asTypeOf(addrBundle).index === a2.asTypeOf(addrBundle).index)
}

abstract class La32rCacheBundle(implicit cacheConfig: La32rCacheConfig) extends Bundle with HasEulaCoreParameter with HasLa32rCacheConst
abstract class La32rCacheModule(implicit cacheConfig: La32rCacheConfig) extends Module with HasEulaCoreParameter with HasLa32rCacheConst with HasEulaCoreLog

trait HasLa32rCacheIO {
  implicit val cacheConfig: La32rCacheConfig
  class La32rCacheIO(implicit val cacheConfig: La32rCacheConfig) extends Bundle with HasEulaCoreParameter with HasLa32rCacheConst {
    val in = Flipped(new SimpleBusUC(userBits = userBits, idBits = idBits))
    val flush = Input(UInt(2.W))
    val out = new SimpleBusC
    val mmio = new SimpleBusUC
  }
  val io = IO(new La32rCacheIO())
}

class La32rMetaBundle(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheBundle {
  val tag = Output(UInt(TagBits.W))
  val valid = Output(Bool())
  val dirty = Output(Bool())

  def apply(tag: UInt, valid: Bool, dirty: Bool) = {
    this.tag := tag
    this.valid := valid
    this.dirty := dirty
    this
  }
}

class La32rDataBundle(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheBundle {
  val data = Output(UInt(DataBits.W))

  def apply(data: UInt) = {
    this.data := data
    this
  }
}

class La32rStage1IO(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheBundle {
  val req = new SimpleBusReqBundle(userBits = userBits, idBits = idBits)
}

// meta read
class La32rCacheStage1(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheModule {
  class CacheStage1IO extends Bundle {
    val in = Flipped(Decoupled(new SimpleBusReqBundle(userBits = userBits, idBits = idBits)))
    val out = Decoupled(new La32rStage1IO)
    val metaReadBus = CacheMetaArrayReadBus()
    val dataReadBus = CacheDataArrayReadBus()
    val cacheInvalidStall = Input(Bool())
  }
  val io = IO(new CacheStage1IO)

  if (ro) when (io.in.fire()) { assert(!io.in.bits.isWrite()) }
  Debug(io.in.fire(), "[L1$] cache stage1, addr in: %x, user: %x id: %x\n", io.in.bits.addr, io.in.bits.user.getOrElse(0.U), io.in.bits.id.getOrElse(0.U))

  // read meta array and data array
  val readBusValid = io.in.valid && io.out.ready && !io.cacheInvalidStall
  io.metaReadBus.apply(valid = readBusValid, setIdx = getMetaIdx(io.in.bits.addr))
  io.dataReadBus.apply(valid = readBusValid, setIdx = getDataIdx(io.in.bits.addr))

  io.out.bits.req := io.in.bits
  io.out.valid := io.in.valid && io.metaReadBus.req.ready && io.dataReadBus.req.ready && !io.cacheInvalidStall
  io.in.ready := io.out.ready && io.metaReadBus.req.ready && io.dataReadBus.req.ready && !io.cacheInvalidStall

  Debug("in.ready = %d, in.valid = %d, out.valid = %d, out.ready = %d, addr = %x, cmd = %x, dataReadBus.req.valid = %d\n", io.in.ready, io.in.valid, io.out.valid, io.out.ready, io.in.bits.addr, io.in.bits.cmd, io.dataReadBus.req.valid)
}

class La32rStage2IO(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheBundle {
  val req = new SimpleBusReqBundle(userBits = userBits, idBits = idBits)
  val metas = Vec(Ways, new La32rMetaBundle)
  val datas = Vec(Ways, new La32rDataBundle)
  val hit = Output(Bool())
  val waymask = Output(UInt(Ways.W))
  val mmio = Output(Bool())
  val isForwardData = Output(Bool())
  val forwardData = Output(CacheDataArrayWriteBus().req.bits)
}

// check
class La32rCacheStage2(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheModule with HasLa32rCSRConst {
  class CacheStage2IO extends Bundle {
    val in = Flipped(Decoupled(new La32rStage1IO))
    val out = Decoupled(new La32rStage2IO)
    val metaReadResp = Flipped(Vec(Ways, new La32rMetaBundle))
    val dataReadResp = Flipped(Vec(Ways, new La32rDataBundle))
    val metaWriteBus = Input(CacheMetaArrayWriteBus())
    val dataWriteBus = Input(CacheDataArrayWriteBus())
  }
  val io = IO(new CacheStage2IO)

  val req = io.in.bits.req
  val addr = req.addr.asTypeOf(addrBundle)

  val isForwardMeta = io.in.valid && io.metaWriteBus.req.valid && io.metaWriteBus.req.bits.setIdx === getMetaIdx(req.addr)
  val isForwardMetaReg = RegInit(false.B)
  when (isForwardMeta) { isForwardMetaReg := true.B }
  when (io.in.fire() || !io.in.valid) { isForwardMetaReg := false.B }
  val forwardMetaReg = RegEnable(next = io.metaWriteBus.req.bits, enable = isForwardMeta, init = 0.U.asTypeOf(io.metaWriteBus.req.bits))

  val metaWay = Wire(Vec(Ways, chiselTypeOf(forwardMetaReg.data)))
  val pickForwardMeta = isForwardMetaReg || isForwardMeta
  val forwardMeta = Mux(isForwardMeta, io.metaWriteBus.req.bits, forwardMetaReg)
  val forwardWaymask = forwardMeta.waymask.getOrElse("1".U).asBools
  forwardWaymask.zipWithIndex.map { case (w, i) =>
    metaWay(i) := Mux(pickForwardMeta && w, forwardMeta.data, io.metaReadResp(i))
  }

  val hitVec = VecInit(metaWay.map(m => m.valid && (m.tag === addr.tag) && io.in.valid)).asUInt
  val victimWaymask = if (Ways > 1) (1.U << LFSR64()(log2Up(Ways)-1,0)) else "b1".U

  val invalidVec = VecInit(metaWay.map(m => !m.valid)).asUInt
  val hasInvalidWay = invalidVec.orR
  val refillInvalidWaymask = Mux(invalidVec >= 8.U, "b1000".U,
    Mux(invalidVec >= 4.U, "b0100".U,
      Mux(invalidVec >= 2.U, "b0010".U, "b0001".U)))

  // val waymask = Mux(io.out.bits.hit, hitVec, victimWaymask)
  val waymask = Mux(io.out.bits.hit, hitVec, Mux(hasInvalidWay, refillInvalidWaymask, victimWaymask))
  when(PopCount(waymask) > 1.U){
    metaWay.map(m => Debug("[ERROR] metaWay %x metat %x reqt %x\n", m.valid, m.tag, addr.tag))
    io.metaReadResp.map(m => Debug("[ERROR] metaReadResp %x metat %x reqt %x\n", m.valid, m.tag, addr.tag))
    Debug("[ERROR] forwardMetaReg isForwardMetaReg %x %x metat %x wm %b\n", isForwardMetaReg, forwardMetaReg.data.valid, forwardMetaReg.data.tag, forwardMetaReg.waymask.get)
    Debug("[ERROR] forwardMeta isForwardMeta %x %x metat %x wm %b\n", isForwardMeta, io.metaWriteBus.req.bits.data.valid, io.metaWriteBus.req.bits.data.tag, io.metaWriteBus.req.bits.waymask.get)
  }
  when(PopCount(waymask) > 1.U){Debug("[ERROR] hit %b wmask %b hitvec %b\n", io.out.bits.hit, forwardMeta.waymask.getOrElse("1".U), hitVec)}
  assert(!(io.in.valid && PopCount(waymask) > 1.U))

  val ismmio = WireInit(false.B)

  io.out.bits.metas := metaWay
  io.out.bits.hit := io.in.valid && hitVec.orR && !ismmio
  io.out.bits.waymask := waymask
  io.out.bits.datas := io.dataReadResp

  if (cacheName == "icache") {
    ismmio := req.user.get.asTypeOf(new ImmuUserBundle).mat === StronglyOrderedUncached
  } else {
    ismmio := req.user.get.asTypeOf(new DmmuUserBundle).mat === StronglyOrderedUncached
  }
  io.out.bits.mmio := ismmio

  val isForwardData = io.in.valid && (io.dataWriteBus.req match { case r =>
    r.valid && r.bits.setIdx === getDataIdx(req.addr)
  })
  val isForwardDataReg = RegInit(false.B)
  when (isForwardData) { isForwardDataReg := true.B }
  when (io.in.fire() || !io.in.valid) { isForwardDataReg := false.B }
  val forwardDataReg = RegEnable(next = io.dataWriteBus.req.bits, enable = isForwardData, init = 0.U.asTypeOf(io.dataWriteBus.req.bits))
  io.out.bits.isForwardData := isForwardDataReg || isForwardData
  io.out.bits.forwardData := Mux(isForwardData, io.dataWriteBus.req.bits, forwardDataReg)

  io.out.bits.req <> req
  io.out.valid := io.in.valid
  io.in.ready := !io.in.valid || io.out.fire()

  Debug("[isFD:%d isFDreg:%d inFire:%d invalid:%d \n", isForwardData, isForwardDataReg, io.in.fire(), io.in.valid)
  Debug("[isFM:%d isFMreg:%d metawreq:%x widx:%x ridx:%x \n", isForwardMeta, isForwardMetaReg, io.metaWriteBus.req.valid, io.metaWriteBus.req.bits.setIdx, getMetaIdx(req.addr))
}

// writeback
class La32rCacheStage3(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheModule {
  class CacheStage3IO extends Bundle {
    val in = Flipped(Decoupled(new La32rStage2IO))
    val out = Decoupled(new SimpleBusRespBundle(userBits = userBits, idBits = idBits))
    val isFinish = Output(Bool())
    val flush = Input(Bool())
    val dataReadBus = CacheDataArrayReadBus()
    val dataWriteBus = CacheDataArrayWriteBus()
    val metaWriteBus = CacheMetaArrayWriteBus()

    val mem = new SimpleBusUC
    val mmio = new SimpleBusUC
    val cohResp = Decoupled(new SimpleBusRespBundle)

    // use to distinguish prefetch request and normal request
    val dataReadRespToL1 = Output(Bool())
    val debug = new Bundle() {
      val s3_mmio = Output(Bool())
      val s3_hit = Output(Bool())
      val s3_tlbexcp = Output(Bool())
      val s3_miss = Output(Bool())
      val s3_probe = Output(Bool())
      val s3_hitReadBurst = Output(Bool())
      val s3_meta = Output(new La32rMetaBundle)
      val s3_state = Output(UInt(4.W))
      val s3_state2 = Output(UInt(2.W))
    }
    val tryGetSem = Output(Bool())
    val receiveSem = Input(Bool())
    val releaseSem = Output(Bool())
  }
  val io = IO(new CacheStage3IO)

  val metaWriteArb = Module(new Arbiter(CacheMetaArrayWriteBus().req.bits, 2))
  val dataWriteArb = Module(new Arbiter(CacheDataArrayWriteBus().req.bits, 2))

  val hasTlbExcp = WireInit(false.B)
  if (cacheName == "icache") {
    hasTlbExcp := io.in.bits.req.user.get.asTypeOf(new ImmuUserBundle).tlbExcp.asUInt().orR
  } else {
    hasTlbExcp := io.in.bits.req.user.get.asTypeOf(new DmmuUserBundle).tlbExcp.asUInt().orR
  }

  val req = io.in.bits.req
  val addr = req.addr.asTypeOf(addrBundle)
  val mmio = io.in.valid && io.in.bits.mmio && !hasTlbExcp
  val hit = io.in.valid && (io.in.bits.hit || hasTlbExcp)
  val miss = io.in.valid && !io.in.bits.hit && !hasTlbExcp
  val probe = io.in.valid && hasCoh.B && req.isProbe()
  val hitReadBurst = hit && req.isReadBurst()
  val meta = Mux1H(io.in.bits.waymask, io.in.bits.metas)

  io.debug.s3_hit := hit
  io.debug.s3_meta := meta
  io.debug.s3_miss := miss
  io.debug.s3_mmio := mmio
  io.debug.s3_probe := probe
  io.debug.s3_hitReadBurst := hitReadBurst
  io.debug.s3_tlbexcp := hasTlbExcp
  assert(!(mmio && hit), "MMIO request should not trigger hit at stage3")

  // TODO : THIS IS UGLY
  io.tryGetSem := (miss || mmio) && !io.flush


  val useForwardData = io.in.bits.isForwardData && io.in.bits.waymask === io.in.bits.forwardData.waymask.getOrElse("b1".U)
  val dataReadArray = Mux1H(io.in.bits.waymask, io.in.bits.datas).data
  val dataRead = Mux(useForwardData, io.in.bits.forwardData.data.data, dataReadArray)
  val wordMask = Mux(!ro.B && req.isWrite(), MaskExpand(req.wmask), 0.U(DataBits.W))

  val writeL2BeatCnt = Counter(LineBeats)
  when(io.out.fire() && (req.cmd === SimpleBusCmd.writeBurst || req.isWriteLast())) {
    writeL2BeatCnt.inc()
  }

  val hitWrite = hit && req.isWrite() && !hasTlbExcp
  val dataHitWriteBus = Wire(CacheDataArrayWriteBus()).apply(
    data = Wire(new La32rDataBundle).apply(MaskData(dataRead, req.wdata, wordMask)),
    valid = hitWrite, setIdx = Cat(addr.index, Mux(req.cmd === SimpleBusCmd.writeBurst || req.isWriteLast(), writeL2BeatCnt.value, addr.wordIndex)), waymask = io.in.bits.waymask)

  val metaHitWriteBus = Wire(CacheMetaArrayWriteBus()).apply(
    valid = hitWrite && !meta.dirty, setIdx = getMetaIdx(req.addr), waymask = io.in.bits.waymask,
    data = Wire(new La32rMetaBundle).apply(tag = meta.tag, valid = true.B, dirty = (!ro).B)
  )

  val s_idle :: s_memReadReq :: s_memReadResp :: s_memWriteReq :: s_memWriteResp :: s_mmioReq :: s_mmioResp :: s_wait_resp :: s_release :: Nil = Enum(9)
  val state = RegInit(s_idle)
  io.debug.s3_state := state
  val needFlush = RegInit(false.B)

  io.releaseSem := (state === s_idle) || (state === s_wait_resp)

  when (io.flush && (state =/= s_idle)) { needFlush := true.B }
  when (io.out.fire() && needFlush) { needFlush := false.B }

  val readBeatCnt = Counter(LineBeats)
  val writeBeatCnt = Counter(LineBeats)

  val s2_idle :: s2_dataReadWait :: s2_dataOK :: Nil = Enum(3)
  val state2 = RegInit(s2_idle)
  io.debug.s3_state2 := state2

  io.dataReadBus.apply(valid = (state === s_memWriteReq || state === s_release) && (state2 === s2_idle),
    setIdx = Cat(addr.index, Mux(state === s_release, readBeatCnt.value, writeBeatCnt.value)))
  val dataWay = RegEnable(next = io.dataReadBus.resp.data, enable = state2 === s2_dataReadWait, init = 0.U.asTypeOf(io.dataReadBus.resp.data))
  val dataHitWay = Mux1H(io.in.bits.waymask, dataWay).data

  switch (state2) {
    is (s2_idle) { when (io.dataReadBus.req.fire()) { state2 := s2_dataReadWait } }
    is (s2_dataReadWait) { state2 := s2_dataOK }
    is (s2_dataOK) { when (io.mem.req.fire() || io.cohResp.fire() || hitReadBurst && io.out.ready) { state2 := s2_idle } }
  }

  // critical word first read
  val raddr = (if (XLEN == 64) Cat(req.addr(PAddrBits-1,3), 0.U(3.W))
  else Cat(req.addr(PAddrBits-1,2), 0.U(2.W)))
  // dirty block addr
  val waddr = Cat(meta.tag, addr.index, 0.U(OffsetBits.W))
  val cmd = Mux(state === s_memReadReq, SimpleBusCmd.readBurst,
    Mux((writeBeatCnt.value === (LineBeats - 1).U), SimpleBusCmd.writeLast, SimpleBusCmd.writeBurst))
  io.mem.req.bits.apply(addr = Mux(state === s_memReadReq, raddr, waddr),
    cmd = cmd, size = (if (XLEN == 64) "b11".U else "b10".U),
    wdata = dataHitWay, wmask = Fill(DataBytes, 1.U))

  io.mem.resp.ready := true.B
  io.mem.req.valid := (state === s_memReadReq) || ((state === s_memWriteReq) && (state2 === s2_dataOK))

  // mmio
  io.mmio.req.bits := req
  io.mmio.resp.ready := true.B
  io.mmio.req.valid := (state === s_mmioReq)

  val afterFirstRead = RegInit(false.B)
  val alreadyOutFire = RegEnable(true.B, init = false.B, io.out.fire())
  val readingFirst = !afterFirstRead && io.mem.resp.fire() && (state === s_memReadResp)
  val inRdataRegDemand = RegEnable(next = Mux(mmio, io.mmio.resp.bits.rdata, io.mem.resp.bits.rdata),
    enable = Mux(mmio, state === s_mmioResp, readingFirst), init = 0.U)

  // probe
  io.cohResp.valid := ((state === s_idle) && probe) ||
    ((state === s_release) && (state2 === s2_dataOK))
  io.cohResp.bits.rdata := dataHitWay
  val releaseLast = Counter(state === s_release && io.cohResp.fire(), LineBeats)._2
  io.cohResp.bits.cmd := Mux(state === s_release, Mux(releaseLast, SimpleBusCmd.readLast, 0.U),
    Mux(hit, SimpleBusCmd.probeHit, SimpleBusCmd.probeMiss))

  val respToL1Fire = hitReadBurst && io.out.ready && state2 === s2_dataOK
  val respToL1Last = Counter((state === s_idle || state === s_release && state2 === s2_dataOK) && hitReadBurst && io.out.ready, LineBeats)._2

  switch (state) {
    is (s_idle) {
      afterFirstRead := false.B
      alreadyOutFire := false.B

      when (probe) {
        when (io.cohResp.fire()) {
          state := Mux(hit, s_release, s_idle)
          readBeatCnt.value := addr.wordIndex
        }
      } .elsewhen (hitReadBurst && io.out.ready) {
        state := s_release
        readBeatCnt.value := Mux(addr.wordIndex === (LineBeats - 1).U, 0.U, (addr.wordIndex + 1.U))
      } .elsewhen ((miss || mmio) && !io.flush && io.receiveSem) {
        state := Mux(mmio, s_mmioReq, Mux(!ro.B && meta.dirty, s_memWriteReq, s_memReadReq))
      }
    }

    is (s_mmioReq) { when (io.mmio.req.fire()) { state := s_mmioResp } }
    is (s_mmioResp) { when (io.mmio.resp.fire()) { state := s_wait_resp } }

    is (s_release) {
      when (io.cohResp.fire() || respToL1Fire) { readBeatCnt.inc() }
      when (probe && io.cohResp.fire() && releaseLast || respToL1Fire && respToL1Last) { state := s_idle }
    }

    is (s_memReadReq) { when (io.mem.req.fire()) {
      state := s_memReadResp
      readBeatCnt.value := addr.wordIndex
    }}

    is (s_memReadResp) {
      when (io.mem.resp.fire()) {
        afterFirstRead := true.B
        readBeatCnt.inc()
        when (req.cmd === SimpleBusCmd.writeBurst) { writeL2BeatCnt.value := 0.U }
        when (io.mem.resp.bits.isReadLast()) { state := s_wait_resp }
      }
    }

    is (s_memWriteReq) {
      when (io.mem.req.fire()) { writeBeatCnt.inc() }
      when (io.mem.req.bits.isWriteLast() && io.mem.req.fire()) { state := s_memWriteResp }
    }

    is (s_memWriteResp) { when (io.mem.resp.fire()) { state := s_memReadReq } }
    is (s_wait_resp) { when (io.out.fire() || needFlush || alreadyOutFire) { state := s_idle } }
  }

  val dataRefill = MaskData(io.mem.resp.bits.rdata, req.wdata, Mux(readingFirst, wordMask, 0.U(DataBits.W)))
  val dataRefillWriteBus = Wire(CacheDataArrayWriteBus).apply(
    valid = (state === s_memReadResp) && io.mem.resp.fire(), setIdx = Cat(addr.index, readBeatCnt.value),
    data = Wire(new La32rDataBundle).apply(dataRefill), waymask = io.in.bits.waymask)

  dataWriteArb.io.in(0) <> dataHitWriteBus.req
  dataWriteArb.io.in(1) <> dataRefillWriteBus.req
  io.dataWriteBus.req <> dataWriteArb.io.out

  val metaRefillWriteBus = Wire(CacheMetaArrayWriteBus()).apply(
    valid = (state === s_memReadResp) && io.mem.resp.fire() && io.mem.resp.bits.isReadLast(),
    data = Wire(new La32rMetaBundle).apply(valid = true.B, tag = addr.tag, dirty = !ro.B && req.isWrite()),
    setIdx = getMetaIdx(req.addr), waymask = io.in.bits.waymask
  )

  metaWriteArb.io.in(0) <> metaHitWriteBus.req
  metaWriteArb.io.in(1) <> metaRefillWriteBus.req
  io.metaWriteBus.req <> metaWriteArb.io.out

  if (cacheLevel == 2) {
    when ((state === s_memReadResp) && io.mem.resp.fire() && req.isReadBurst()) {
      // readBurst request miss
      io.out.bits.rdata := dataRefill
      io.out.bits.cmd := Mux(io.mem.resp.bits.isReadLast(), SimpleBusCmd.readLast, SimpleBusCmd.readBurst)
    }.elsewhen (req.isWriteLast() || req.cmd === SimpleBusCmd.writeBurst) {
      // writeBurst/writeLast request, no matter hit or miss
      io.out.bits.rdata := Mux(hit, dataRead, inRdataRegDemand)
      io.out.bits.cmd := DontCare
    }.elsewhen (hitReadBurst && state === s_release) {
      // readBurst request hit
      io.out.bits.rdata := dataHitWay
      io.out.bits.cmd := Mux(respToL1Last, SimpleBusCmd.readLast, SimpleBusCmd.readBurst)
    }.otherwise {
      io.out.bits.rdata := Mux(hit, dataRead, inRdataRegDemand)
      io.out.bits.cmd := req.cmd
    }
  } else {
    io.out.bits.rdata := Mux(hit, dataRead, inRdataRegDemand)
    io.out.bits.cmd := Mux(io.in.bits.req.isRead(), SimpleBusCmd.readLast, Mux(io.in.bits.req.isWrite(), SimpleBusCmd.writeResp, DontCare))//DontCare, added by lemover
  }
  io.out.bits.user.zip(req.user).map { case (o,i) => o := i }
  io.out.bits.id.zip(req.id).map { case (o,i) => o := i }

  io.out.valid := io.in.valid && Mux(req.isBurst() && (cacheLevel == 2).B,
    Mux(req.isWrite() && (hit || !hit && state === s_wait_resp), true.B, (state === s_memReadResp && io.mem.resp.fire() && req.cmd === SimpleBusCmd.readBurst)) || (respToL1Fire && respToL1Last && state === s_release),
    Mux(probe, false.B, Mux(hit, true.B, Mux(req.isWrite() || mmio, state === s_wait_resp, afterFirstRead && !alreadyOutFire)))
  )

  // With critical-word first, the pipeline registers between
  // s2 and s3 can not be overwritten before a missing request
  // is totally handled. We use io.isFinish to indicate when the
  // request really ends.
  io.isFinish := Mux(probe, io.cohResp.fire() && Mux(miss, state === s_idle, (state === s_release) && releaseLast),
    Mux(hit || req.isWrite(), io.out.fire(), (state === s_wait_resp) && (io.out.fire() || alreadyOutFire))
  )

  io.in.ready := io.out.ready && (state === s_idle && !hitReadBurst) && !miss && !probe
  io.dataReadRespToL1 := hitReadBurst && (state === s_idle && io.out.ready || state === s_release && state2 === s2_dataOK)

  assert(!(metaHitWriteBus.req.valid && metaRefillWriteBus.req.valid))
  assert(!(dataHitWriteBus.req.valid && dataRefillWriteBus.req.valid))
  assert(!(!ro.B && io.flush), "only allow to flush icache")
  Debug(" metaread idx %x waymask %b metas %x%x:%x %x%x:%x %x%x:%x %x%x:%x %x\n", getMetaIdx(req.addr), io.in.bits.waymask.asUInt, io.in.bits.metas(0).valid, io.in.bits.metas(0).dirty, io.in.bits.metas(0).tag, io.in.bits.metas(1).valid, io.in.bits.metas(1).dirty, io.in.bits.metas(1).tag, io.in.bits.metas(2).valid, io.in.bits.metas(2).dirty, io.in.bits.metas(2).tag, io.in.bits.metas(3).valid, io.in.bits.metas(3).dirty, io.in.bits.metas(3).tag, io.in.bits.datas.asUInt)
  Debug(io.metaWriteBus.req.fire(), "%d: [" + cacheName + " S3]: metawrite idx %x wmask %b meta %x%x:%x\n", GTimer(), io.metaWriteBus.req.bits.setIdx, io.metaWriteBus.req.bits.waymask.get, io.metaWriteBus.req.bits.data.valid, io.metaWriteBus.req.bits.data.dirty, io.metaWriteBus.req.bits.data.tag)
  Debug(" in.ready = %d, in.valid = %d, hit = %x, state = %d, addr = %x cmd:%d probe:%d isFinish:%d\n", io.in.ready, io.in.valid, hit, state, req.addr, req.cmd, probe, io.isFinish)
  Debug(" out.valid:%d rdata:%x cmd:%d user:%x id:%x \n", io.out.valid, io.out.bits.rdata, io.out.bits.cmd, io.out.bits.user.getOrElse(0.U), io.out.bits.id.getOrElse(0.U))
  Debug(" DHW: (%d, %d), data:%x setIdx:%x MHW:(%d, %d)\n", dataHitWriteBus.req.valid, dataHitWriteBus.req.ready, dataHitWriteBus.req.bits.data.asUInt, dataHitWriteBus.req.bits.setIdx, metaHitWriteBus.req.valid, metaHitWriteBus.req.ready)
  Debug(" DreadCache: %x \n", io.in.bits.datas.asUInt)
  Debug(" useFD:%d isFD:%d FD:%x DreadArray:%x dataRead:%x inwaymask:%x FDwaymask:%x \n", useForwardData, io.in.bits.isForwardData, io.in.bits.forwardData.data.data, dataReadArray, dataRead, io.in.bits.waymask, io.in.bits.forwardData.waymask.getOrElse("b1".U))
  Debug(io.dataWriteBus.req.fire(), "[WB] waymask: %b data:%x setIdx:%x\n",
    io.dataWriteBus.req.bits.waymask.get.asUInt, io.dataWriteBus.req.bits.data.asUInt, io.dataWriteBus.req.bits.setIdx)
  Debug((state === s_memWriteReq) && io.mem.req.fire(), "[COUTW] cnt %x addr %x data %x cmd %x size %x wmask %x tag %x idx %x waymask %b \n", writeBeatCnt.value, io.mem.req.bits.addr, io.mem.req.bits.wdata, io.mem.req.bits.cmd, io.mem.req.bits.size, io.mem.req.bits.wmask, addr.tag, getMetaIdx(req.addr), io.in.bits.waymask)
  Debug((state === s_memReadReq) && io.mem.req.fire(), "[COUTR] addr %x tag %x idx %x waymask %b \n", io.mem.req.bits.addr, addr.tag, getMetaIdx(req.addr), io.in.bits.waymask)
  Debug((state === s_memReadResp) && io.mem.resp.fire(), "[COUTR] cnt %x data %x tag %x idx %x waymask %b \n", readBeatCnt.value, io.mem.resp.bits.rdata, addr.tag, getMetaIdx(req.addr), io.in.bits.waymask)
}

class La32rCache(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheModule with HasLa32rCacheIO {
  assert(cacheName == "icache" || cacheName == "dcache")
  // cpu pipeline
  val s1 = Module(new La32rCacheStage1)
  val s2 = Module(new La32rCacheStage2)
  val s3 = Module(new La32rCacheStage3)
  val metaArray = Module(new SRAMTemplateWithArbiter(nRead = 2, new La32rMetaBundle, set = Sets, way = Ways, shouldReset = true))
  val dataArray = Module(new SRAMTemplateWithArbiter(nRead = 3, new La32rDataBundle, set = Sets * LineBeats, way = Ways, shouldReset = true))

  val cacheInvalidUnit = if (cacheName == "icache") Module(new ICacheInvalidUnit()) else Module(new DCacheInvalidUnit())

  val arb = Module(new Arbiter(new SimpleBusReqBundle(userBits = userBits, idBits = idBits), hasCohInt + 1))
  arb.io.in(hasCohInt + 0) <> io.in.req

  s1.io.in <> arb.io.out

  s1.io.cacheInvalidStall := cacheInvalidUnit.io.inprocess
  /*
  val s2BlockByPrefetch = if (cacheLevel == 2) {
      s2.io.out.valid && s3.io.in.valid && s3.io.in.bits.req.isPrefetch() && !s3.io.in.ready
    } else { false.B }
  */
  PipelineConnect(s1.io.out, s2.io.in, s2.io.out.fire(), io.flush(0))
  PipelineConnect(s2.io.out, s3.io.in, s3.io.isFinish, io.flush(1))
  io.in.resp <> s3.io.out
  s3.io.flush := io.flush(1)

  val memXbar = Module(new SimpleBusCrossbarNto1(2))
  memXbar.io.in(0) <> cacheInvalidUnit.io.mem
  memXbar.io.in(1) <> s3.io.mem
  io.out.mem <> memXbar.io.out

  io.mmio <> s3.io.mmio

  io.in.resp.valid := Mux(s3.io.out.valid && s3.io.out.bits.isPrefetch(), false.B, s3.io.out.valid || s3.io.dataReadRespToL1)

  if (hasCoh) {
    val cohReq = io.out.coh.req.bits
    // coh does not have user signal, any better code?
    val coh = Wire(new SimpleBusReqBundle(userBits = userBits, idBits = idBits))
    coh.apply(addr = cohReq.addr, cmd = cohReq.cmd, size = cohReq.size, wdata = cohReq.wdata, wmask = cohReq.wmask)
    arb.io.in(0).bits := coh
    arb.io.in(0).valid := io.out.coh.req.valid
    io.out.coh.req.ready := arb.io.in(0).ready
    io.out.coh.resp <> s3.io.cohResp
  } else {
    io.out.coh.req.ready := true.B
    io.out.coh.resp := DontCare
    io.out.coh.resp.valid := false.B
    s3.io.cohResp.ready := true.B
  }

  val metaWriteArb = Module(new Arbiter(CacheMetaArrayWriteBus().req.bits, 2))

  metaArray.io.r(0) <> cacheInvalidUnit.io.metaReadBus
  metaArray.io.r(1) <> s1.io.metaReadBus
  dataArray.io.r(0) <> cacheInvalidUnit.io.dataReadBus
  dataArray.io.r(1) <> s1.io.dataReadBus
  dataArray.io.r(2) <> s3.io.dataReadBus

  metaWriteArb.io.in(0) <> cacheInvalidUnit.io.metaWriteBus.req
  metaWriteArb.io.in(1) <> s3.io.metaWriteBus.req
  metaArray.io.w.req <> metaWriteArb.io.out
  dataArray.io.w <> s3.io.dataWriteBus

  s2.io.metaReadResp := s1.io.metaReadBus.resp.data
  s2.io.dataReadResp := s1.io.dataReadBus.resp.data
  s2.io.dataWriteBus := s3.io.dataWriteBus
  s2.io.metaWriteBus := s3.io.metaWriteBus

  dontTouch(s3.io.debug)

  val cacopValid = WireInit(false.B)
  val cacopCode = WireInit(0.U(5.W))
  val cacopVA = WireInit(0.U(VAddrBits.W))
  val cacopPA = WireInit(0.U(PAddrBits.W))
  BoringUtils.addSink(cacopValid, "CACOP_VALID")
  BoringUtils.addSink(cacopCode, "CACOP_CODE")
  BoringUtils.addSink(cacopVA, "CACOP_VA")
  BoringUtils.addSink(cacopPA, "CACOP_PA")

  // ibar
  val flushICache = WireInit(false.B)
  val flushDCache = WireInit(false.B)
  val dcacheFlushDone = WireInit(false.B)
  val icacheFlushDone = WireInit(false.B)
  BoringUtils.addSink(flushICache, "FLUSH_ICACHE")
  BoringUtils.addSink(flushDCache, "FLUSH_DCACHE")
  if (cacheName == "icache") {
    BoringUtils.addSource(icacheFlushDone, "ICACHE_FLUSH_DONE")
  } else {
    BoringUtils.addSource(dcacheFlushDone, "DCACHE_FLUSH_DONE")
  }

  val ibarflush = Mux((cacheName == "icache").asBool(), flushICache, flushDCache)
  val cacopflush = cacopValid & Mux((cacheName == "icache").asBool(), cacopCode(2, 0) === 0.U, cacopCode(2, 0) === 1.U)

  cacheInvalidUnit.io.req.valid := cacopflush || ibarflush
  cacheInvalidUnit.io.req.bits.isCacop := cacopflush
  cacheInvalidUnit.io.req.bits.isIbar := ibarflush
  cacheInvalidUnit.io.req.bits.cacopCode := cacopCode
  cacheInvalidUnit.io.req.bits.cacopVA := cacopVA
  cacheInvalidUnit.io.req.bits.cacopPA := cacopPA

  dcacheFlushDone := cacheInvalidUnit.io.done
  icacheFlushDone := cacheInvalidUnit.io.done

  cacheInvalidUnit.io.cachePipelineClear := true.B // !s2.io.in.valid && !s3.io.in.valid


  // TODO : THIS IS UGLY
  val tryGetSem = WireInit(false.B)
  val receiveSem = WireInit(false.B)
  val releaseSem = WireInit(false.B)
  if (cacheName == "icache") {
    BoringUtils.addSource(tryGetSem, "icacheTryGetSem")
    BoringUtils.addSink(receiveSem, "sendICacheSem")
    BoringUtils.addSource(releaseSem, "icacheReleaseSem")
  } else {
    BoringUtils.addSource(tryGetSem, "dcacheTryGetSem")
    BoringUtils.addSink(receiveSem, "sendDCacheSem")
    BoringUtils.addSource(releaseSem, "dcacheReleaseSem")
  }
  tryGetSem := s3.io.tryGetSem
  releaseSem := s3.io.releaseSem
  s3.io.receiveSem := receiveSem

//  if (EnableOutOfOrderExec) {
//    BoringUtils.addSource(s3.io.out.fire() && s3.io.in.bits.hit, "perfCntCondM" + cacheName + "Hit")
//    BoringUtils.addSource(s3.io.in.valid && !s3.io.in.bits.hit, "perfCntCondM" + cacheName + "Loss")
//    BoringUtils.addSource(s1.io.in.fire(), "perfCntCondM" + cacheName + "Req")
//  }
  // io.in.dump(cacheName + ".in")
  Debug("InReq(%d, %d) InResp(%d, %d) \n", io.in.req.valid, io.in.req.ready, io.in.resp.valid, io.in.resp.ready)
  Debug("{IN s1:(%d,%d), s2:(%d,%d), s3:(%d,%d)} {OUT s1:(%d,%d), s2:(%d,%d), s3:(%d,%d)}\n", s1.io.in.valid, s1.io.in.ready, s2.io.in.valid, s2.io.in.ready, s3.io.in.valid, s3.io.in.ready, s1.io.out.valid, s1.io.out.ready, s2.io.out.valid, s2.io.out.ready, s3.io.out.valid, s3.io.out.ready)
  when (s1.io.in.valid) { Debug(p"[${cacheName}.S1]: ${s1.io.in.bits}\n") }
  when (s2.io.in.valid) { Debug(p"[${cacheName}.S2]: ${s2.io.in.bits.req}\n") }
  when (s3.io.in.valid) { Debug(p"[${cacheName}.S3]: ${s3.io.in.bits.req}\n") }
  //s3.io.mem.dump(cacheName + ".mem")
}

class CacheInvalidUnitIO(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheBundle {
  val metaReadBus = CacheMetaArrayReadBus()
  val dataReadBus = CacheDataArrayReadBus()
  val metaWriteBus = CacheMetaArrayWriteBus()
  val mem = new SimpleBusUC
  val req = Flipped(Decoupled(new Bundle {
    val isCacop = Output(Bool())
    val isIbar = Output(Bool())
    val cacopCode = Output(UInt(5.W))
    val cacopVA = Output(UInt(VAddrBits.W))
    val cacopPA = Output(UInt(PAddrBits.W))
  }))
  val cachePipelineClear = Input(Bool())
  val inprocess = Output(Bool())
  val done = Output(Bool())
}

class AbstractCacheInvalidUnit(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheModule {
  val io = IO(new CacheInvalidUnitIO())
}

// for all cacop inst and ibar, just invalid all cacheline in icache for simplicity of implementation
class ICacheInvalidUnit(implicit override val cacheConfig: La32rCacheConfig) extends AbstractCacheInvalidUnit {
  assert(cacheName == "icache" && ro)

  val s_idle :: s_wait_cache_clear :: s_invalid :: s_done :: Nil = Enum(4)
  val state = RegInit(s_idle)

  io.inprocess := io.req.valid || (state =/= s_idle)
  io.done := (state === s_done)

  io.req.ready := true.B

  val receiveReq = io.req.valid && (state === s_idle)
  val reqBits = RegEnable(next = io.req.bits, enable = receiveReq, init = 0.U.asTypeOf(io.req.bits))

  val setCnt = RegInit(0.U(IndexBits.W))

  switch (state) {
    is (s_idle) {
      when(io.req.valid) {
        state := s_wait_cache_clear
      }
    }

    is (s_wait_cache_clear) {
      when (io.cachePipelineClear) {
        state := s_invalid
        setCnt := 0.U
      }
    }

    is (s_invalid) {
      when (io.metaWriteBus.req.fire) { setCnt := setCnt + 1.U }
      when (setCnt === (Sets - 1).U) { state := s_done }
    }

    is (s_done) {
      state := s_idle
    }
  }

  io.metaWriteBus.apply(
    valid = (state === s_invalid),
    setIdx = setCnt,
    waymask = Fill(Ways, 1.U),
    data = Wire(new La32rMetaBundle()).apply(tag = 0.U, valid = false.B, dirty = false.B))

  io.metaReadBus.req.valid := false.B
  io.metaReadBus.req.bits.setIdx := 0.U
  io.dataReadBus.req.valid := false.B
  io.dataReadBus.req.bits.setIdx := 0.U
  io.mem.req.valid := false.B
  io.mem.req.bits.apply(addr = 0.U, cmd = 0.U, size = 0.U, wdata = 0.U, wmask = 0.U, user = 0.U, id = 0.U)
  io.mem.resp.ready := true.B

}

// for all cacop inst and ibar, just invalid all cacheline and writeback all dirty data in dcache for simplicity of implementation
class DCacheInvalidUnit(implicit override val cacheConfig: La32rCacheConfig) extends AbstractCacheInvalidUnit {
  assert(cacheName == "dcache" && !ro)

  val s_idle :: s_wait_cache_clear :: s_storeTag :: s_readMetaReq :: s_readMetaResp :: s_readData :: s_readDataResp :: s_writeBackData :: s_done :: Nil = Enum(9)
  val state = RegInit(s_idle)

  val s_way_idle :: s_writeBackWay :: s_writeBackLine :: s_writeResp :: s_way_done :: Nil = Enum(5)
  val way_state = RegInit(s_way_idle)

  io.inprocess := io.req.valid || (state =/= s_idle)
  io.done := (state === s_done)

  io.req.ready := true.B

  val receiveReq = io.req.valid && (state === s_idle)
  val reqBits = RegEnable(next = io.req.bits, enable = receiveReq, init = 0.U.asTypeOf(io.req.bits))

  val isStoreTag = reqBits.isCacop && (reqBits.cacopCode(4, 3) === 0.U) // see la32r spec 4.2.2.1

  val metaSetCnt = RegInit(0.U(IndexBits.W))
  val dataSetCnt = RegInit(0.U(IndexBits.W))
  val storeTagCnt = RegInit(0.U(IndexBits.W))
  val readBeatCnt = RegInit(0.U(log2Up(LineBeats).W))
  val readMetaVec = HoldUnless(io.metaReadBus.resp.data, RegNext(io.metaReadBus.req.fire))
  val readDataVec = RegInit(VecInit(Seq.fill(Ways)(VecInit(Seq.fill(LineBeats)(0.U(DataBits.W))))))

  val dirtyWayVec = VecInit(readMetaVec.map(way => way.valid && way.dirty))
  val hasDirtyWay = dirtyWayVec.reduce(_||_)

  val isLastSet = (Sets - 1).U
  val isLastBeat = (LineBeats - 1).U

  val tryGetSem = WireInit(false.B)
  val receiveSem = WireInit(false.B)
  val releaseSem = WireInit(false.B)

  switch (state) {
    is(s_idle) {
      when(io.req.valid) {
        state := s_wait_cache_clear
      }
    }

    is(s_wait_cache_clear) {
      // TODO : remove io.cachePipelineClear because it must be true
      when(io.cachePipelineClear && !isStoreTag && receiveSem) {
        state := s_readMetaReq
        metaSetCnt := 0.U
      }
      when (io.cachePipelineClear && isStoreTag) {
        state := s_storeTag
        storeTagCnt := 0.U
      }
    }

    is (s_storeTag) {
      when (io.metaWriteBus.req.fire) { storeTagCnt := storeTagCnt + 1.U }
      when (storeTagCnt === isLastSet) { state := s_done }
    }

    is (s_readMetaReq) {
      when (io.metaReadBus.req.fire) { state := s_readMetaResp }
    }

    is (s_readMetaResp) {
      when (hasDirtyWay && io.metaWriteBus.req.fire) {
        state := s_readData
        dataSetCnt := metaSetCnt
        readBeatCnt := 0.U
      }
      when (!hasDirtyWay && io.metaWriteBus.req.fire) {
        state := s_readMetaReq
        metaSetCnt := metaSetCnt + 1.U
      }
      when (!hasDirtyWay && metaSetCnt === isLastSet && io.metaWriteBus.req.fire) { state := s_done }
    }

    is (s_readData) {
      when (io.dataReadBus.req.fire()) { state := s_readDataResp }
    }

    is (s_readDataResp) {
      readBeatCnt := readBeatCnt + 1.U
      when (readBeatCnt === isLastBeat) { state := s_writeBackData }
        .otherwise { state := s_readData }
    }

    is (s_writeBackData) {
      when (way_state === s_way_done) {
        when (metaSetCnt === isLastSet) { state := s_done }
          .otherwise {
            metaSetCnt := metaSetCnt + 1.U
            state := s_readMetaReq
          }
      }
    }

    is(s_done) {
      state := s_idle
    }
  }

  // meta read
  io.metaReadBus.apply(valid = state === s_readMetaReq, setIdx = metaSetCnt)
  // meta clear write
  io.metaWriteBus.apply(
    valid = (state === s_readMetaResp) || (state === s_storeTag),
    setIdx = Mux(state === s_storeTag, storeTagCnt, metaSetCnt),
    waymask = Fill(Ways, 1.U),
    data = Wire(new La32rMetaBundle()).apply(tag = 0.U, valid = false.B, dirty = false.B))

  // data read
  io.dataReadBus.apply(valid = state === s_readData, setIdx = Cat(dataSetCnt, readBeatCnt))
  val dataReadBack = RegNext(io.dataReadBus.req.fire())
  when (dataReadBack) {
    readDataVec.zip(io.dataReadBus.resp.data).map{ case (way, data) => way(readBeatCnt) := data.data }
  }

  val wayCnt = RegInit(0.U(log2Up(Ways).W))
  val writeBeatCnt = RegInit(0.U(log2Up(LineBeats).W))
  val isLastWay = (Ways - 1).U

  val nowMeta = RegInit(0.U.asTypeOf(new La32rMetaBundle())) //Reg(new La32rMetaBundle())
  val nowData = RegInit(0.U.asTypeOf(new La32rDataBundle())) // Reg(new La32rDataBundle())

  switch(way_state) {
    is(s_way_idle) {
      when (state === s_writeBackData) {
        wayCnt := 0.U
        way_state := s_writeBackWay
      }
    }

    is (s_writeBackWay) {
      when (dirtyWayVec(wayCnt)) {
        writeBeatCnt := 0.U
        nowMeta := readMetaVec(wayCnt)
        nowData.data := readDataVec(wayCnt)(0.U)
        way_state := s_writeBackLine
      } .elsewhen(wayCnt === isLastWay) {
        way_state := s_way_done
      }.otherwise {
        wayCnt := wayCnt + 1.U
      }
    }

    is (s_writeBackLine) {
      when (io.mem.req.fire) {
        writeBeatCnt := writeBeatCnt + 1.U
        nowData.data := readDataVec(wayCnt)(writeBeatCnt + 1.U)
      }
      when (io.mem.req.fire && writeBeatCnt === isLastBeat) {
        way_state := s_writeResp
      }
    }

    is (s_writeResp) {
      when (io.mem.resp.fire) {
        wayCnt := wayCnt + 1.U
        way_state := s_writeBackWay
      }
      when (io.mem.resp.fire && wayCnt === isLastWay) {
        way_state := s_way_done
      }
    }

    is (s_way_done) {
      way_state := s_way_idle
    }
  }

  // write back
  io.mem.req.valid := way_state === s_writeBackLine
  io.mem.req.bits.apply(
    addr = Cat(nowMeta.tag, dataSetCnt, 0.U(OffsetBits.W)),
    cmd = Mux(writeBeatCnt === isLastBeat, SimpleBusCmd.writeLast, SimpleBusCmd.writeBurst),
    size = (if (XLEN == 64) "b11".U else "b10".U),
    wdata = nowData.data,
    wmask = Fill(DataBytes, 1.U))

  io.mem.resp.ready := way_state === s_writeResp

  BoringUtils.addSource(tryGetSem, "dcacheInvUnitTryGetSem")
  BoringUtils.addSink(receiveSem, "sendDcacheInvUnitSem")
  BoringUtils.addSource(releaseSem, "dcacheInvUnitReleaseSem")
  tryGetSem := (state === s_wait_cache_clear) && !isStoreTag
  releaseSem := state === s_idle || state === s_done

}

class La32rCache_fake(implicit val cacheConfig: La32rCacheConfig) extends La32rCacheModule with HasLa32rCacheIO with HasLa32rCSRConst {
  assert(cacheName == "icache" || cacheName == "dcache")
  val s_idle :: s_wait_sem :: s_memReq :: s_memResp :: s_mmioReq :: s_mmioResp :: s_wait_resp :: Nil = Enum(7)
  val state = RegInit(s_idle)

  val memuser = RegEnable(next = io.in.req.bits.user.getOrElse(0.U), enable = io.in.req.fire(), init = 0.U)
  io.in.resp.bits.user.zip(if (userBits > 0) Some(memuser) else None).map { case (o, i) => o := i }

  val ismmio = WireInit(false.B)
  if (cacheName == "icache") {
    ismmio := io.in.req.bits.user.get.asTypeOf(new ImmuUserBundle).mat === StronglyOrderedUncached
  } else {
    ismmio := io.in.req.bits.user.get.asTypeOf(new DmmuUserBundle).mat === StronglyOrderedUncached
  }

  // cache semaphore
  val tryGetSem = WireInit(false.B)
  val receiveSem = WireInit(false.B)
  val releaseSem = WireInit(false.B)
  // cache semaphore
  if (cacheName == "icache") {
    BoringUtils.addSource(tryGetSem, "icacheTryGetSem")
    BoringUtils.addSink(receiveSem, "sendICacheSem")
    BoringUtils.addSource(releaseSem, "icacheReleaseSem")
  } else {
    BoringUtils.addSource(tryGetSem, "dcacheTryGetSem")
    BoringUtils.addSink(receiveSem, "sendDCacheSem")
    BoringUtils.addSource(releaseSem, "dcacheReleaseSem")
    val dcacheInvUnitTryGetSem = WireInit(false.B)
    val receiveDcacheInvUnitSem = WireInit(false.B)
    val dcacheInvUnitReleaseSem = WireInit(false.B)
    BoringUtils.addSource(dcacheInvUnitTryGetSem, "dcacheInvUnitTryGetSem")
    BoringUtils.addSink(receiveDcacheInvUnitSem, "sendDcacheInvUnitSem")
    BoringUtils.addSource(dcacheInvUnitReleaseSem, "dcacheInvUnitReleaseSem")
  }

  val ismmioRec = RegEnable(next = ismmio, enable = io.in.req.fire(), init = false.B)

  val hasTlbExcp = WireInit(false.B)

  val needFlush = RegInit(false.B)
  when (io.flush(0) && (state =/= s_idle)) { needFlush := true.B }
  when (state === s_idle && needFlush) { needFlush := false.B }

  val alreadyOutFire = RegEnable(true.B, init = false.B, io.in.resp.fire())

//  val isInvalidAddr = false.B// !PMA.isValidAddr(io.in.req.bits.addr)

  switch (state) {
    is (s_idle) {
      alreadyOutFire := false.B
      when (io.in.req.fire() && !io.flush(0)) { state := s_wait_sem }
    }
    is (s_wait_sem) {
      when (receiveSem) { state := Mux(ismmioRec, s_mmioReq, s_memReq) }
    }
    is (s_memReq) {
      when (hasTlbExcp) { state := s_wait_resp }
      .elsewhen (io.out.mem.req.fire()) { state := s_memResp }
    }
    is (s_memResp) {
      when (io.out.mem.resp.fire()) { state := s_wait_resp }
    }
    is (s_mmioReq) {
      when (hasTlbExcp) { state := s_wait_resp }
      .elsewhen (io.mmio.req.fire()) { state := s_mmioResp }
    }
    is (s_mmioResp) {
      when (io.mmio.resp.fire() || alreadyOutFire) { state := s_wait_resp }
    }
    is (s_wait_resp) {
      when (io.in.resp.fire() || needFlush || alreadyOutFire) { state := s_idle }
    }
  }

  val reqaddr = RegEnable(next = io.in.req.bits.addr, enable = io.in.req.fire(), init = 0.U)
  val cmd = RegEnable(next = io.in.req.bits.cmd, enable = io.in.req.fire(), init = 0.U)
  val size = RegEnable(next = io.in.req.bits.size, enable = io.in.req.fire(), init = 0.U)
  val wdata = RegEnable(next = io.in.req.bits.wdata, enable = io.in.req.fire(), init = 0.U)
  val wmask = RegEnable(next = io.in.req.bits.wmask, enable = io.in.req.fire(), init = 0.U)

  io.in.req.ready := (state === s_idle)
  io.in.resp.valid := (state === s_wait_resp) && (!needFlush)

  val mmiordata = RegEnable(next = io.mmio.resp.bits.rdata, enable = io.mmio.resp.fire(), init = 0.U)
  val mmiocmd = RegEnable(next = io.mmio.resp.bits.cmd, enable = io.mmio.resp.fire(), init = 0.U)
  val memrdata = RegEnable(next = io.out.mem.resp.bits.rdata, enable = io.out.mem.resp.fire(), init = 0.U)
  val memcmd = RegEnable(next = io.out.mem.resp.bits.cmd, enable = io.out.mem.resp.fire(), init = 0.U)

  io.in.resp.bits.rdata := Mux(ismmioRec, mmiordata, memrdata)
  io.in.resp.bits.cmd := Mux(ismmioRec, mmiocmd, memcmd)


  if (cacheName == "icache") {
    hasTlbExcp := memuser.asTypeOf(new ImmuUserBundle).tlbExcp.asUInt().orR
  } else {
    hasTlbExcp := memuser.asTypeOf(new DmmuUserBundle).tlbExcp.asUInt().orR
  }

  io.out.mem.req.bits.apply(addr = reqaddr,
    cmd = cmd, size = size,
    wdata = wdata, wmask = wmask)
  io.out.mem.req.valid := (state === s_memReq && !hasTlbExcp)
  io.out.mem.resp.ready := true.B

  io.mmio.req.bits.apply(addr = reqaddr,
    cmd = cmd, size = size,
    wdata = wdata, wmask = wmask)
  io.mmio.req.valid := (state === s_mmioReq && !hasTlbExcp)
  io.mmio.resp.ready := true.B

  io.out.coh := DontCare

  val cacopValid = WireInit(false.B)
  val cacopCode = WireInit(0.U(5.W))
  val cacopVA = WireInit(0.U(VAddrBits.W))
  val cacopPA = WireInit(0.U(PAddrBits.W))
  BoringUtils.addSink(cacopValid, "CACOP_VALID")
  BoringUtils.addSink(cacopCode, "CACOP_CODE")
  BoringUtils.addSink(cacopVA, "CACOP_VA")
  BoringUtils.addSink(cacopPA, "CACOP_PA")

  // ibar
  val flushICache = WireInit(false.B)
  val flushDCache = WireInit(false.B)
  val dcacheFlushDone = WireInit(false.B)
  val icacheFlushDone = WireInit(false.B)
  BoringUtils.addSink(flushICache, "FLUSH_ICACHE")
  BoringUtils.addSink(flushDCache, "FLUSH_DCACHE")
  if (cacheName == "icache") {
    BoringUtils.addSource(icacheFlushDone, "ICACHE_FLUSH_DONE")
  } else {
    BoringUtils.addSource(dcacheFlushDone, "DCACHE_FLUSH_DONE")
  }

  val flush = Mux((cacheName == "icache").asBool(), flushICache, flushDCache)
  val s_flush_idle :: s_flush_doing :: s_flush_done :: Nil = Enum(3)
  val flush_state = RegInit(s_flush_idle)
  val flush_counter = RegInit(0.U(5.W))
  switch (flush_state) {
    is (s_flush_idle) {
      when (flush || cacopValid) {
        flush_state := s_flush_doing
        flush_counter := 0.U
      }
    }
    is (s_flush_doing) {
      flush_counter := flush_counter + 1.U // just for simulate flush time delay
      when (flush_counter === 16.U) { flush_state := s_flush_done }
    }
    is (s_flush_done) { flush_state := s_flush_idle }
  }
  dcacheFlushDone := flush_state === s_flush_done
  icacheFlushDone := flush_state === s_flush_done


  tryGetSem := state === s_wait_sem
  releaseSem := state === s_idle || state === s_wait_resp

  Debug(io.in.req.fire(), p"in.req: ${io.in.req.bits}\n")
  Debug(io.out.mem.req.fire(), p"out.mem.req: ${io.out.mem.req.bits}\n")
  Debug(io.out.mem.resp.fire(), p"out.mem.resp: ${io.out.mem.resp.bits}\n")
  Debug(io.in.resp.fire(), p"in.resp: ${io.in.resp.bits}\n")
}

object La32rCache {
  def apply(in: SimpleBusUC, mmio: SimpleBusUC, flush: UInt, enable: Boolean = false)(implicit cacheConfig: La32rCacheConfig) = {
    val cache = if (enable) Module(new La32rCache()) else Module(new La32rCache_fake())
    cache.io.in <> in
    cache.io.flush := flush
    mmio <> cache.io.mmio
    cache
  }
}