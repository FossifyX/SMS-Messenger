package org.fossify.messages.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ExportBlockedNumbersDialog
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityManageBlockedKeywordsBinding
import org.fossify.messages.dialogs.AddBlockedKeywordDialog
import org.fossify.messages.dialogs.ExportBlockedKeywordsDialog
import org.fossify.messages.dialogs.ManageBlockedKeywordsAdapter
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.toArrayList
import org.fossify.messages.helpers.BlockedKeywordsExporter
import org.fossify.messages.helpers.BlockedKeywordsImporter
import java.io.FileOutputStream
import java.io.OutputStream

class ManageBlockedKeywordsActivity : BaseSimpleActivity(), RefreshRecyclerViewListener {

    override fun getAppIconIDs() = intent.getIntegerArrayListExtra(APP_ICON_IDS) ?: ArrayList()

    override fun getAppLauncherName() = intent.getStringExtra(APP_LAUNCHER_NAME) ?: ""

    private val binding by viewBinding(ActivityManageBlockedKeywordsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        isMaterialActivity = true
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        updateBlockedKeywords()
        setupOptionsMenu()

        updateMaterialActivityViews(
            mainCoordinatorLayout = binding.blockKeywordsCoordinator,
            nestedView = binding.manageBlockedKeywordsList,
            useTransparentNavigation = true,
            useTopSearchMenu = false
        )
        setupMaterialScrollListener(scrollingView = binding.manageBlockedKeywordsList, toolbar = binding.blockKeywordsToolbar)
        updateTextColors(binding.manageBlockedKeywordsWrapper)

        binding.manageBlockedKeywordsPlaceholder2.apply {
            underlineText()
            setTextColor(getProperPrimaryColor())
            setOnClickListener {
                addOrEditBlockedKeyword()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(binding.blockKeywordsToolbar, NavigationIcon.Arrow)
    }

    private fun setupOptionsMenu() {
        binding.blockKeywordsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.add_blocked_keyword -> {
                    addOrEditBlockedKeyword()
                    true
                }

                R.id.export_blocked_keywords -> {
                    tryExportBlockedNumbers()
                    true
                }

                R.id.import_blocked_keywords -> {
                    tryImportBlockedKeywords()
                    true
                }

                else -> false
            }
        }
    }

    private val exportActivityResultLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        try {
            val outputStream = uri?.let { contentResolver.openOutputStream(it) }
            if (outputStream != null) {
                exportBlockedKeywordsTo(outputStream)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private val importActivityResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        try {
            if (uri != null) {
                tryImportBlockedKeywordsFromFile(uri)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }


    private fun tryImportBlockedKeywords() {
        if (isQPlus()) {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"

                try {
                    importActivityResultLauncher.launch(type)
                } catch (e: ActivityNotFoundException) {
                    toast(org.fossify.commons.R.string.system_service_disabled, Toast.LENGTH_LONG)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        } else {
            handlePermission(PERMISSION_READ_STORAGE) { isAllowed ->
                if (isAllowed) {
                    pickFileToImportBlockedKeywords()
                }
            }
        }
    }

    private fun pickFileToImportBlockedKeywords() {
        FilePickerDialog(this) {
            importBlockedKeywords(it)
        }
    }

    private fun tryImportBlockedKeywordsFromFile(uri: Uri) {
        when (uri.scheme) {
            "file" -> importBlockedKeywords(uri.path!!)
            "content" -> {
                val tempFile = getTempFile("blocked", "blocked_keywords.txt")
                if (tempFile == null) {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                    return
                }

                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val out = FileOutputStream(tempFile)
                    inputStream!!.copyTo(out)
                    importBlockedKeywords(tempFile.absolutePath)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }

            else -> toast(org.fossify.commons.R.string.invalid_file_format)
        }
    }

    private fun importBlockedKeywords(path: String) {
        ensureBackgroundThread {
            val result = BlockedKeywordsImporter(this).importBlockedKeywords(path)
            toast(
                when (result) {
                    BlockedKeywordsImporter.ImportResult.IMPORT_OK -> org.fossify.commons.R.string.importing_successful
                    BlockedKeywordsImporter.ImportResult.IMPORT_FAIL -> org.fossify.commons.R.string.no_items_found
                }
            )
            updateBlockedKeywords()
        }
    }

    private fun exportBlockedKeywordsTo(outputStream: OutputStream?) {
        ensureBackgroundThread {
            val blockedKeywords = config.blockedKeywords.toArrayList()
            if (blockedKeywords.isEmpty()) {
                toast(org.fossify.commons.R.string.no_entries_for_exporting)
            } else {
                BlockedKeywordsExporter.exportBlockedKeywords(blockedKeywords, outputStream) {
                    toast(
                        when (it) {
                            ExportResult.EXPORT_OK -> org.fossify.commons.R.string.exporting_successful
                            else -> org.fossify.commons.R.string.exporting_failed
                        }
                    )
                }
            }
        }
    }

    private fun tryExportBlockedNumbers() {
        if (isQPlus()) {
            ExportBlockedKeywordsDialog(this, config.lastBlockedKeywordExportPath, true) { file ->
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addCategory(Intent.CATEGORY_OPENABLE)

                    try {
                        exportActivityResultLauncher.launch(file.name)
                    } catch (e: ActivityNotFoundException) {
                        toast(org.fossify.commons.R.string.system_service_disabled, Toast.LENGTH_LONG)
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            }
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE) { isAllowed ->
                if (isAllowed) {
                    ExportBlockedNumbersDialog(this, config.lastBlockedKeywordExportPath, false) { file ->
                        getFileOutputStream(file.toFileDirItem(this), true) { out ->
                            exportBlockedKeywordsTo(out)
                        }
                    }
                }
            }
        }
    }

    override fun refreshItems() {
        updateBlockedKeywords()
    }

    private fun updateBlockedKeywords() {
        ensureBackgroundThread {
            val blockedKeywords = config.blockedKeywords
            runOnUiThread {
                ManageBlockedKeywordsAdapter(this, blockedKeywords.toArrayList(), this, binding.manageBlockedKeywordsList) {
                    addOrEditBlockedKeyword(it as String)
                }.apply {
                    binding.manageBlockedKeywordsList.adapter = this
                }

                binding.manageBlockedKeywordsPlaceholder.beVisibleIf(blockedKeywords.isEmpty())
                binding.manageBlockedKeywordsPlaceholder2.beVisibleIf(blockedKeywords.isEmpty())
            }
        }
    }

    fun addOrEditBlockedKeyword(keyword: String? = null) {
        AddBlockedKeywordDialog(this, keyword) {
            updateBlockedKeywords()
        }
    }
}
