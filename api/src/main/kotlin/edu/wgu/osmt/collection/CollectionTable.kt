package edu.wgu.osmt.collection

import edu.wgu.osmt.db.TableWithUpdateMapper
import edu.wgu.osmt.keyword.KeywordTable
import edu.wgu.osmt.richskill.RichSkillDescriptorTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.`java-time`.datetime
import org.jetbrains.exposed.sql.statements.UpdateBuilder


object CollectionTable: TableWithUpdateMapper<CollectionUpdateObject>, LongIdTable("Collection") {
    override val table: LongIdTable = this
    override val creationDate = datetime("creationDate")
    override val updateDate = datetime("updateDate")
    val uuid = varchar("uuid", 36).uniqueIndex()
    val name = text("name")
    val author = reference(
        "author_id",
        KeywordTable,
        onDelete = ReferenceOption.RESTRICT,
        onUpdate = ReferenceOption.CASCADE
    ).nullable()

    override fun updateBuilderApplyFromUpdateObject(
        updateBuilder: UpdateBuilder<Number>,
        updateObject: CollectionUpdateObject
    ) {
        super.updateBuilderApplyFromUpdateObject(updateBuilder, updateObject)
        updateObject.name?.let { updateBuilder[name] = it }
        updateObject.author?.let {
            if (it.t != null) {
                updateBuilder[author] = EntityID<Long>(it.t.id!!, KeywordTable)
            } else {
                updateBuilder[author] = null
            }
        }
    }
}

object CollectionSkills : Table("CollectionSkills") {
    val collectionId = reference(
        "collection_id",
        CollectionTable,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE
    ).index()
    val skillId = reference(
        "skill_id",
        RichSkillDescriptorTable,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE
    ).index()
    override val primaryKey = PrimaryKey(collectionId, skillId, name = "PK_CollectionSkills_c_rs")

    fun create(collectionId: Long, skillId: Long) {
        insert {
            it[this.collectionId] = EntityID(collectionId, CollectionTable)
            it[this.skillId] = EntityID(skillId, RichSkillDescriptorTable)
        }
    }
    fun delete(collectionId: Long, skillId: Long) {
        deleteWhere {
            (CollectionSkills.collectionId eq collectionId) and
            (CollectionSkills.skillId eq skillId)
        }
    }
}