// boundary: reflection. Each construct is tried on its own; a failure is recorded as FAIL <exception>, not thrown.
fun <T> attempt(f: () -> T): String = try { f().toString() } catch (t: Throwable) { "FAIL " + t.javaClass.simpleName }

data class Point(val x: Int, val y: String)

response["forName_jdk_class"] = attempt { Class.forName("java.util.ArrayList").simpleName }
response["getMethod_invoke_jdk"] = attempt { String::class.java.getMethod("length").invoke("abcd") }
response["declaredFields_user_class"] = attempt { Point::class.java.declaredFields.map { it.name }.sorted().joinToString(",") }
response["kotlin_reflect_members"] = attempt { Point::class.members.map { it.name }.sorted().first() }
response["kotlin_class_name"] = attempt { Point::class.simpleName }
response["dynamic_proxy"] = attempt {
    val p = java.lang.reflect.Proxy.newProxyInstance(Runnable::class.java.classLoader, arrayOf(Runnable::class.java)) { _, _, _ -> null }
    p is Runnable
}
