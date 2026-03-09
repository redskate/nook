package solutions.semweb.nook.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface TrustedContactDao {
    @Query("SELECT * FROM trusted_contacts WHERE isActive = 1 ORDER BY displayName")
    fun getAllSync(): List<TrustedContactEntity>

    @Query("SELECT * FROM trusted_contacts ORDER BY displayName")
    fun getAll(): List<TrustedContactEntity>

    @Query("SELECT * FROM trusted_contacts WHERE contactId = :contactId LIMIT 1")
    fun getById(contactId: String): TrustedContactEntity?

    @Insert
    fun insert(contact: TrustedContactEntity): Long

    @Update
    fun update(contact: TrustedContactEntity)

    @Delete
    fun delete(contact: TrustedContactEntity)

    @Query("DELETE FROM trusted_contacts WHERE contactId = :contactId")
    fun deleteById(contactId: String)

    @Query("SELECT * FROM trusted_contacts WHERE phoneNumber = :phoneNumber LIMIT 1")
    fun findByPhoneNumber(phoneNumber: String): TrustedContactEntity?

    @Query("SELECT COUNT(*) FROM trusted_contacts WHERE isActive = 1")
    fun countActive(): Int

    @Query("UPDATE trusted_contacts SET isActive = :isActive WHERE contactId = :contactId")
    fun setActive(contactId: String, isActive: Boolean)
}