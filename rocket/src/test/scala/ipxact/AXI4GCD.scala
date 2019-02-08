// See LICENSE for license details.

package ipxact

import chisel3._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.regmapper.{RegField, RegFieldDesc, RegReadFn, RegWriteFn}

case class GCDParams(
  width: Int = 32
)

class GCD(params: GCDParams = GCDParams()) extends Module {
  val io = IO(new Bundle {
    val a  = Input(UInt(params.width.W))
    val b  = Input(UInt(params.width.W))
    val e  = Input(Bool())
    val z  = Output(UInt(params.width.W))
    val v  = Output(Bool())
  })

  Annotated.params(this, params)

  val x = Reg(UInt(32.W))
  val y = Reg(UInt(32.W))
  when (x > y)   { x := x -% y }
    .otherwise     { y := y -% x }
  when (io.e) { x := io.a; y := io.b }
  io.z := x
  io.v := y === 0.U
}

class AXI4GCD extends LazyModule()(Parameters.empty) {

  val regs = AXI4RegisterNode(AddressSet(0x0, 0xFFFF), beatBytes = 4, concurrency = 1)

  val ioMemNode = BundleBridgeSource(() => AXI4Bundle(AXI4BundleParameters(addrBits = 8, dataBits = 32, idBits = 1)))

  regs :=
    BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
    ioMemNode

  val ioMem = InModuleBody { ioMemNode.makeIO() }



  lazy val module = new LazyModuleImp(this) {
    Annotated.addressMapping(ioMem,
      Seq(AddressMapEntry(
        AddressRange(regs.address.base, regs.address.mask),
        ResourcePermissions(true, true, false, false, false),
        Seq("GCD"),
    )))
    Annotated.params(ioMem, ioMem.params)

    val a = Reg(UInt(32.W))
    val b = Reg(UInt(32.W))

    val gcd = Module(new GCD())
    gcd.io.a := a
    gcd.io.b := b

    val mapping = Seq(
      0x0 -> Seq(RegField(4, a, RegFieldDesc(name = "a", desc = "First term in GCD"))),
      0x1 -> Seq(RegField(4, b, RegFieldDesc(name = "b", desc = "Second term in GCD"))),
      0x2 -> Seq(RegField(4,
        RegReadFn((ivalid: Bool, oready: Bool) => (true.B, true.B, gcd.io.v)),
        RegWriteFn((ivalid: Bool, oready: Bool, data: UInt) => {
          gcd.io.e := ivalid && oready
          (true.B, true.B)
        }),
        RegFieldDesc(name = "enable/valid", desc = "Write to set enable, read to check valid"),
      )),
      0x3 -> Seq(RegField.r(4, gcd.io.z, RegFieldDesc(name = "z", desc = "Output of GCD"))),
    )

    GenRegDescsAnno.anno(
      this,
      0x0,
      mapping:_*
    )

    regs.regmap(mapping:_*)
  }
}

object PrintMe extends App {
  val dut = LazyModule(new AXI4GCD)
  // println(chisel3.Driver.emit(() => dut.module))
  chisel3.Driver.execute(Array("-X", "verilog"), () => dut.module)
}
