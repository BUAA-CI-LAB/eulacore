package top

object DefaultSettings {
  def apply() = Map(
    "FPGAPlatform" -> false,
    "EnableDebug" -> true,
    "HasDcache" -> true,
    "HasIcache" -> true,
    "IcacheSize" -> 8, // kbytes
    "DcacheSize" -> 8, // kbytes
    "ResetVector" -> 0x1c000000L,
    "EnablePerfOutput" -> false,
    "HasIMMU" -> true,
    "HasDMMU" -> true,
    "TlbEntryNum" -> 32,
    "ConfregBase1" -> 0xbfaf0000L, // see chiplab/IP/BRIDGE/bridge_1x2.v : line 48/49 and line 107/108
    "ConfregBase2" -> 0x1faf0000L,
    "ConfregSize" -> 0x10000L,
    "RAMBase" -> 0x0L,
    "RAMSize" -> 0x100000000L, // 4096 MB memory
    "UartBase" -> 0x1fe001e0L, // uart for linux
    "UartSize" -> 0x10L,
  )
}

object Settings {
  var settings: Map[String, AnyVal] = DefaultSettings()
  def get(field: String) = {
    settings(field).asInstanceOf[Boolean]
  }
  def getLong(field: String) = {
    settings(field).asInstanceOf[Long]
  }
  def getInt(field: String) = {
    settings(field).asInstanceOf[Int]
  }
}
