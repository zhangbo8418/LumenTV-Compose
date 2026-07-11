package com.corner.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.corner.catvodcore.bean.Api
import com.corner.database.entity.Config
import com.corner.database.entity.Site
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

@Dao
interface SiteDao{
    @Query("SELECT * FROM Site where `key` = :key")
    suspend fun find(key:String): Site?

    @Query("SELECT * FROM Site where configId = :configId")
    fun findByConfigId(configId: Long): Flow<List<Site>>

    @Query("SELECT * FROM Site")
    fun getAllSites(): Flow<List<Site>>

    @Query("UPDATE Site SET searchable = :searchable WHERE `key` = :siteKey")
    suspend fun updateSearchable(siteKey: String, searchable: Long)

    @Update
    suspend fun update(site:Site)

    @Insert
    suspend fun save(sites: List<Site>)

    suspend fun update(cfg:Config, api: Api): MutableSet<com.corner.catvodcore.bean.Site> {
        val sites = api.sites
        val siteList = findByConfigId(cfg.id).firstOrNull().orEmpty()
        if (siteList.isEmpty() && sites.isNotEmpty()) {
            save(api.sites.map { it.toDbSite(configId = cfg.id) })
        } else if (siteList.isNotEmpty() && sites.isNotEmpty()) {
            for (site in sites) {
                siteList.firstOrNull { it.key == site.key }?.let { filter ->
                    site.searchable = filter.searchable?.toInt()
                    site.changeable = filter.changeable?.toInt()
                    site.id = filter.id
                }
            }
        }
        return sites
    }
}