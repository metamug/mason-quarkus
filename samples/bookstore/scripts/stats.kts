// the average rating of the book, from the reviews step
val rows = steps["reviews"] as? List<*> ?: emptyList<Any?>()
val ratings = rows.mapNotNull { r -> (r as? Map<*, *>)?.entries?.firstOrNull { it.key.toString().equals("rating", true) }?.value as? Number }
response["count"] = ratings.size
response["average"] = if (ratings.isEmpty()) null else Math.round(ratings.map { it.toDouble() }.average() * 10) / 10.0
