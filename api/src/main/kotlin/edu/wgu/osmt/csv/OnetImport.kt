package edu.wgu.osmt.csv

import com.opencsv.bean.CsvBindByName
import edu.wgu.osmt.jobcode.JobCodeRepository
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Imports O*NET codes in CSV format
 *  - @see <a href="https://www.onetcenter.org/database.html#occ"></a>
 *  - download `Occupation Data` in Excel format
 *  - convert Excel -> csv
 *  - import _after_ running [[BlsImport]]
 *  - see `Imports` instructions in api/README.md
 */
@Component
class OnetImport : CsvImport<OnetJobCode> {
    override val log: Logger = LoggerFactory.getLogger(OnetImport::class.java)

    override val csvRowClass = OnetJobCode::class.java

    @Autowired
    private lateinit var jobCodeRepository: JobCodeRepository

    override fun handleRows(rows: List<OnetJobCode>) {
        log.info("Processing ${rows.size} rows...")
        for (row in rows) transaction {
            log.info("Importing ${row.title} - ${row.code}")
            val jobCode =
                row.code?.let { jobCodeRepository.findByCodeOrCreate(it, JobCodeRepository.`O*NET_FRAMEWORK`) }

            val detailed = row.detailed()?.let { jobCodeRepository.findBlsCode(it) }

            // Optimization, only fetch these if detailed failed
            val broad = detailed ?: row.broad()?.let{ jobCodeRepository.findBlsCode(it)}
            val major = detailed ?: row.major()?.let{ jobCodeRepository.findBlsCode(it)}
            val minor = detailed ?: row.minor()?.let{ jobCodeRepository.findBlsCode(it)}

            jobCode?.let {
                it.name = row.title
                it.description = row.description
                it.detailed = row.detailed()
                it.broad = detailed?.broad ?: broad?.broad
                it.minor = detailed?.minor ?: minor?.minor
                it.major = detailed?.major ?: major?.major
                it.framework = JobCodeRepository.`O*NET_FRAMEWORK`
            }
        }
    }
}

class OnetJobCode : CsvRow, HasCodeHierarchy {
    @CsvBindByName(column = "O*NET-SOC Code")
    override var code: String? = null

    @CsvBindByName(column = "Title")
    var title: String? = null

    @CsvBindByName(column = "Description")
    var description: String? = null
}