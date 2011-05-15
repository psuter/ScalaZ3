import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers

class Arrays extends FunSuite with ShouldMatchers {
  import z3.scala._

  test("Arrays") {
    val z3 = new Z3Context("MODEL" -> true)

    val is = z3.mkIntSort
    val intArraySort = z3.mkArraySort(is, is)
    val array1 = z3.mkFreshConst("arr", intArraySort)
    val array2 = z3.mkFreshConst("arr", intArraySort)
    val x = z3.mkFreshConst("x", is)

    // array1 = [ 42, 42, 42, ... ]
    z3.assertCnstr(z3.mkEq(array1,z3.mkConstArray(is, z3.mkInt(42, is))))
    // x = array1[6]
    z3.assertCnstr(z3.mkEq(x, z3.mkSelect(array1, z3.mkInt(6, is))))
    // array2 = array1[x - 40 -> 0]
    z3.assertCnstr(z3.mkEq(array2, z3.mkStore(array1, z3.mkSub(x, z3.mkInt(40, is)), z3.mkInt(0, is))))

    // "reading" the default value of array2 (should be 42)
    val fourtyTwo = z3.mkFreshConst("ft", is)
    z3.assertCnstr(z3.mkEq(fourtyTwo, z3.mkArrayDefault(array2)))

    val (result, model) = z3.checkAndGetModel

    println("model is")
    println(model)
    result should equal(Some(true))

    val array1Evaluated = model.eval(array1)
    array1Evaluated should be ('defined)
    array1Evaluated match {
      case Some(ae) =>
        val array1Val = model.getArrayValue(ae)
        array1Val should be ('defined)
		println("When evaluated, array1 is: " + array1Val)
        array1Val match {
          case Some(av) =>
            model.evalAs[Int](av._2) should equal (Some(42))
          case None =>
        }
      case None =>
    }

    val array2Evaluated = model.eval(array2)
    array2Evaluated should be ('defined)
    array2Evaluated match {
        case Some(ae) =>
            val array2Val = model.getArrayValue(ae)
            array2Val should be ('defined)
			println("When evaluated, array2 is: " + array2Val)
            array2Val match {
                case Some(av) =>
                    av._1(z3.mkInt(2, z3.mkIntSort)) should equal (z3.mkInt(0,
                                z3.mkIntSort))
                case None =>
            }
        case None =>
    }

    model.evalAs[Int](fourtyTwo) should equal (Some(42))
  }
}

