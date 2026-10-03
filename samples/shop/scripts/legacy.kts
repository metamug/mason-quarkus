// the shop scenario's Groovy script, rewritten in Kotlin (Groovy is dropped): request parameters are in params
val who = params["who"] ?: ""
response["message"] = "Hello " + who
response["length"] = who.length
