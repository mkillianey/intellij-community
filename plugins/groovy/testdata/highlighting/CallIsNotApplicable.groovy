class IntCategory {
  static def call(Integer i, int q) {
    return "$q"
  }
}

use(IntCategory) {
  print 2<warning descr="'2' cannot be applied to '(java.lang.Integer, java.lang.Integer)'">(3, 4)</warning>
}