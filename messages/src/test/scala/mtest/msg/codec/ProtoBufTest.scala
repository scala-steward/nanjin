package mtest.msg.codec

import com.github.chenharryhua.nanjin.messages.kafka.codec.{KPB, SerdeOf}
import mtest.messages.pb.test.MessagePerson
import org.scalacheck.Prop.{forAll, propBoolean}
import org.scalacheck.{Arbitrary, Gen, Properties}
import scalapb.UnknownFieldSet

class ProtoBufTest extends Properties("protobuf") {

  val ser: SerdeOf[KPB[MessagePerson]] = SerdeOf[KPB[MessagePerson]]

  val genPerson: Gen[MessagePerson] = for {
    name <- Gen.asciiStr
    age <- Gen.posNum[Int]
    unknows <- Gen.choose[Int](5, 10)
    along <- Gen.nonEmptyListOf[Long](Gen.posNum[Long])
  } yield MessagePerson(
    name,
    age,
    UnknownFieldSet.empty.withField(unknows, UnknownFieldSet.Field(fixed64 = along.toVector)))

  implicit val arbPerson: Arbitrary[MessagePerson] = Arbitrary(genPerson)

  property("encode/decode identity") = forAll { (p: MessagePerson) =>
    val encoded = ser.avroCodec.avroEncoder.encode(KPB(p))
    val decoded = ser.avroCodec.avroDecoder.decode(encoded)
    decoded.value == p
  }
}
