package com.example.taskreminder.data

/**
 * Represents a group of devices sharing tasks.
 */
data class Group(
    val id: String,
    val joinCode: String,
    val name: String?,
    val createdByIdentityId: String?
)
