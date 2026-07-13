package com.github.blebrowserbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import com.github.blebrowserbridge.databinding.ActivityMainBinding
import java.io.IOException
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothController: BluetoothController
    private lateinit var midiController: MidiController

    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var currentPageIndex = 0

    private var pdfFiles: List<Uri> = emptyList()
    private var currentPdfIndex = -1
    private var isServer = false

    private lateinit var gestureDetector: GestureDetector

    private val tag = "BLE_PDF_SYNC"

    companion object {
        private const val PREF_CROP_MARGINS = "crop_margins"
        private const val PREF_AUTO_HIDE_NAV = "auto_hide_nav"
        private const val PREF_FILE_EXT = "file_ext"
        private const val PREF_FOLDER_URI = "folder_uri"
        private const val PREF_GROUP_CODE = "group_code"
        private const val AUTO_HIDE_DELAY_MS = 4000L
        // "numbers" is a display mode, not a file type: no documents are
        // loaded and only the received section/sheet number is shown
        private const val TYPE_NUMBERS = "numbers"
        private val FILE_EXTENSIONS = listOf("pdf", "jpg", TYPE_NUMBERS)
    }

    private var folderUri: Uri? = null
    private var isImageDoc = false

    private val fileExt: String
        get() = getPreferences(MODE_PRIVATE).getString(PREF_FILE_EXT, "pdf") ?: "pdf"

    private val groupCode: String
        get() = getPreferences(MODE_PRIVATE).getString(PREF_GROUP_CODE, "") ?: ""

    // Auto-hide the reading controls after a few seconds of inactivity
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        if (!binding.setupControls.isVisible && binding.readingControls.isVisible) {
            binding.readingControls.isVisible = false
            hideSystemUI()
        }
    }

    private fun showReadingControls() {
        binding.readingControls.isVisible = true
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        if (getPreferences(MODE_PRIVATE).getBoolean(PREF_AUTO_HIDE_NAV, true)) {
            hideControlsHandler.postDelayed(hideControlsRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun showSetupControls() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        binding.readingControls.isVisible = false
        binding.setupControls.isVisible = true
        supportActionBar?.show()
        showSystemUI()
    }

    // Fullscreen section/sheet display (e.g. "3/15") when no matching
    // document is available on this device
    private fun showBigNumber(text: String) {
        binding.pdfImageView.isVisible = false
        binding.receivedPageText.isVisible = false
        binding.bigNumberText.text = text
        binding.bigNumberText.isVisible = true
    }

    private val cropMargins: Boolean
        get() = getPreferences(MODE_PRIVATE).getBoolean(PREF_CROP_MARGINS, true)

    // Crop mode fits the whole page into the viewport (no scrolling),
    // otherwise the page is shown at full width and scrolls vertically
    private fun applyDisplayMode() {
        val fitViewport = cropMargins
        val height = if (fitViewport) ViewGroup.LayoutParams.MATCH_PARENT
                     else ViewGroup.LayoutParams.WRAP_CONTENT
        // adjustViewBounds would grow the view to the bitmap's aspect ratio
        // and defeat the fit-to-viewport scaling
        binding.pdfImageView.adjustViewBounds = !fitViewport
        binding.pdfContainer.updateLayoutParams { this.height = height }
        binding.pdfImageView.updateLayoutParams { this.height = height }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            if (!isGranted) {
                Log.w(tag, "Permission not granted: $permission")
                Toast.makeText(this, "Permission required: $permission", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                Log.d(tag, "Folder selected: $uri")
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                getPreferences(MODE_PRIVATE).edit().putString(PREF_FOLDER_URI, uri.toString()).apply()
                folderUri = uri
                reloadFolder()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        System.setProperty("java.net.preferIPv4Stack", "true")
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.i(tag, "App started - IPv4 Stack Preferred")

        bluetoothController = BluetoothController(this)
        midiController = MidiController(this)
        
        bluetoothController.onPdfNameReceived = { pdfName, pageIndex ->
            runOnUiThread {
                if (!isServer) {
                    if (pdfName == "server-ready") {
                        binding.receivedPageText.text = getString(R.string.server_ready)
                        return@runOnUiThread
                    }

                    Log.d(tag, "Client received PDF: $pdfName, page: $pageIndex")
                    // Match by the start of the name to handle truncated advertisement data
                    val uriToOpen = pdfFiles.find { uri -> 
                        getFileName(uri)?.startsWith(pdfName, ignoreCase = true) == true 
                    }

                    if (uriToOpen != null) {
                        val newPdfIndex = pdfFiles.indexOf(uriToOpen)
                        if (newPdfIndex != currentPdfIndex) {
                            currentPdfIndex = newPdfIndex
                            loadPDF(uriToOpen)
                        }
                        renderPage(pageIndex)
                    } else {
                        // Not available locally: show the section/sheet number
                        // fullscreen (e.g. "3/15" for 3_15.pdf)
                        Log.w(tag, "No local PDF found starting with: $pdfName")
                        showBigNumber(pdfName.substringBeforeLast('.').replace('_', '/'))
                        binding.readingPageInfo.text = getString(R.string.missing_pdf, pdfName)
                    }
                }
            }
        }
        midiController.onPageChangeRequested = { pageIndex ->
            Log.d(tag, "Page change requested via MIDI: $pageIndex (isServer: $isServer)")
            runOnUiThread {
                renderPage(pageIndex)
            }
        }
        midiController.onSongSelectRequested = { bank, program ->
            // Filenames use the numbers as displayed in SongBook (1-based),
            // the wire values are 0-based: bank 13 + program 19 -> 14_20.pdf
            val target = "${bank + 1}_${program + 1}"
            Log.d(tag, "Song select via MIDI: bank=$bank program=$program -> $target.pdf")
            runOnUiThread {
                val uri = pdfFiles.find { getFileName(it)?.substringBeforeLast('.') == target }
                if (uri != null) {
                    currentPdfIndex = pdfFiles.indexOf(uri)
                    openPdf(uri)
                } else {
                    // No folder selected or file missing: act as a number
                    // display so the musician still sees what to play
                    Log.w(tag, "No PDF named $target.pdf in folder")
                    showBigNumber("${bank + 1}/${program + 1}")
                }
            }
        }

        initBluetooth()
        setupUI()
        setupGestures()
        requestPermissions()

        // Start a role directly, for kiosk setups and the multi-device tests:
        // adb shell am start -n <pkg>/.MainActivity --es autostart client|server
        when (intent.getStringExtra("autostart")) {
            "client" -> binding.root.post { startBLEClient() }
            "server" -> binding.root.post { startBLEServer() }
        }

        Log.d(tag, "App started")
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold = 100
            private val swipeVelocityThreshold = 100

            // Must claim the DOWN event, otherwise the non-clickable views
            // drop the gesture and neither taps nor flings are detected
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold) {
                        if (diffX > 0) {
                            navigatePrevPage()
                        } else {
                            navigateNextPage()
                        }
                        return true
                    }
                } else if (diffY > swipeThreshold && abs(velocityY) > swipeVelocityThreshold) {
                    // Swipe Down from top
                    showPdfSelectionMenu()
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Reading mode: tap zones like a music reader - left third is
                // previous, right third is next, center opens the file list
                // (and brings the controls back so exit stays reachable)
                if (!binding.setupControls.isVisible && (pdfRenderer != null || isImageDoc)) {
                    val width = binding.root.width
                    when {
                        e.x < width / 3f -> navigatePrevPage()
                        e.x > width * 2 / 3f -> navigateNextPage()
                        else -> {
                            showReadingControls()
                            showPdfSelectionMenu()
                        }
                    }
                } else {
                    toggleFullScreen()
                }
                return true
            }
        })

        val touchListener = View.OnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            gestureDetector.onTouchEvent(event)
        }

        binding.pdfImageView.setOnTouchListener(touchListener)
        binding.receivedPageText.setOnTouchListener(touchListener)
        binding.pdfContainer.setOnTouchListener(touchListener)
    }

    private fun initBluetooth() {
        if (!bluetoothController.isBluetoothEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_LONG).show()
            Log.w(tag, "Bluetooth not enabled")
        } else {
            Log.d(tag, "Bluetooth initialized successfully")
        }
    }

    private fun setupUI() {
        binding.selectPdfButton.setOnClickListener {
            selectPdfFolder()
        }

        binding.startServerButton.setOnClickListener {
            startBLEServer()
        }

        binding.startClientButton.setOnClickListener {
            startBLEClient()
        }

        binding.debugButton.setOnClickListener {
            showDebugLog()
        }

        binding.pdfMenuButton.setOnClickListener {
            showReadingControls() // reset the idle timer
            showPdfSelectionMenu()
        }

        binding.prevPageButton.setOnClickListener {
            showReadingControls()
            navigatePrevPage()
        }
        binding.nextPageButton.setOnClickListener {
            showReadingControls()
            navigateNextPage()
        }

        binding.exitReadingButton.setOnClickListener { showSetupControls() }

        binding.cropMarginsCheckbox.isChecked = cropMargins
        binding.cropMarginsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_CROP_MARGINS, isChecked).apply()
            applyDisplayMode()
            renderPage(currentPageIndex)
        }
        binding.autoHideNavCheckbox.isChecked =
            getPreferences(MODE_PRIVATE).getBoolean(PREF_AUTO_HIDE_NAV, true)
        binding.autoHideNavCheckbox.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_AUTO_HIDE_NAV, isChecked).apply()
        }

        binding.fileTypeSpinner.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item,
            FILE_EXTENSIONS.map { it.uppercase() }) {
            // The selected item is rendered on the dark setup panel
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply {
                    (this as? android.widget.TextView)?.setTextColor(Color.WHITE)
                }
        }
        binding.fileTypeSpinner.setSelection(FILE_EXTENSIONS.indexOf(fileExt).coerceAtLeast(0))
        binding.fileTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val ext = FILE_EXTENSIONS[position]
                if (ext != fileExt) {
                    getPreferences(MODE_PRIVATE).edit().putString(PREF_FILE_EXT, ext).apply()
                    reloadFolder()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.groupCodeEdit.setText(groupCode)
        binding.groupCodeEdit.doAfterTextChanged { text ->
            getPreferences(MODE_PRIVATE).edit().putString(PREF_GROUP_CODE, text.toString().trim()).apply()
        }

        applyDisplayMode()

        // Restore the last selected folder if the permission is still held
        getPreferences(MODE_PRIVATE).getString(PREF_FOLDER_URI, null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                folderUri = uri
                listFilesInFolder(uri)
                if (pdfFiles.isNotEmpty()) {
                    currentPdfIndex = 0
                    openPdf(pdfFiles[currentPdfIndex])
                }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (!binding.setupControls.isVisible) {
                showSetupControls()
            } else {
                finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Page-turn pedals typically emulate volume, arrow or page keys
        if ((pdfRenderer != null || isImageDoc) && !binding.setupControls.isVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_PAGE_DOWN -> {
                    navigateNextPage()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_PAGE_UP -> {
                    navigatePrevPage()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun navigatePrevPage() {
        if (isImageDoc) {
            navigatePreviousPdf()
        } else if (currentPageIndex > 0) {
            renderPage(currentPageIndex - 1)
        } else {
            navigatePreviousPdf()
        }
    }

    private fun navigateNextPage() {
        if (isImageDoc) {
            navigateNextPdf()
            return
        }
        pdfRenderer?.let {
            if (currentPageIndex < it.pageCount - 1) {
                renderPage(currentPageIndex + 1)
            } else {
                navigateNextPdf()
            }
        }
    }

    private fun navigatePreviousPdf() {
        if (currentPdfIndex > 0) {
            currentPdfIndex--
            openPdf(pdfFiles[currentPdfIndex])
        } else {
            Toast.makeText(this, "First PDF in folder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateNextPdf() {
        if (currentPdfIndex < pdfFiles.size - 1) {
            currentPdfIndex++
            openPdf(pdfFiles[currentPdfIndex])
        } else {
            Toast.makeText(this, "Last PDF in folder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPdfSelectionMenu() {
        if (pdfFiles.isEmpty()) {
            Toast.makeText(this, "No PDFs found in folder", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = pdfFiles.map { getFileName(it) ?: "Unknown" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select file")
            .setSingleChoiceItems(fileNames, currentPdfIndex) { dialog, which ->
                currentPdfIndex = which
                openPdf(pdfFiles[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun toggleFullScreen() {
        if (binding.setupControls.isVisible) {
            if (pdfFiles.isNotEmpty()) {
                binding.setupControls.isVisible = false
                showReadingControls()
                hideSystemUI()
            }
            return
        }

        if (binding.readingControls.isVisible) {
            hideControlsHandler.removeCallbacks(hideControlsRunnable)
            binding.readingControls.isVisible = false
            hideSystemUI()
        } else {
            showReadingControls()
            showSystemUI()
        }
    }

    private fun hideSystemUI() {
        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            val controller = window.insetsController
            controller?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun selectPdfFolder() {
        Log.d(tag, "Selecting PDF folder")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        pickPdfLauncher.launch(intent)
    }

    private fun reloadFolder() {
        if (fileExt == TYPE_NUMBERS) {
            clearDocument()
            return
        }
        val uri = folderUri ?: return
        listFilesInFolder(uri)
        if (pdfFiles.isNotEmpty()) {
            currentPdfIndex = 0
            openPdf(pdfFiles[currentPdfIndex])
        } else {
            Toast.makeText(this, "No .$fileExt files in folder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearDocument() {
        currentPage?.close()
        currentPage = null
        pdfRenderer?.close()
        pdfRenderer = null
        isImageDoc = false
        pdfFiles = emptyList()
        currentPdfIndex = -1
        binding.pdfImageView.setImageBitmap(null)
        binding.pdfImageView.isVisible = false
    }

    private fun listFilesInFolder(folderUri: Uri) {
        if (fileExt == TYPE_NUMBERS) {
            pdfFiles = emptyList()
            return
        }
        val wantedMime = if (fileExt == "jpg") "image/jpeg" else "application/pdf"
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri))
        val cursor = contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)

        val docs = mutableListOf<Uri>()
        cursor?.use {
            while (it.moveToNext()) {
                val mimeType = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                if (mimeType == wantedMime) {
                    val docId = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                    docs.add(fileUri)
                }
            }
        }
        pdfFiles = docs.sortedBy { getFileName(it) } // Sort files alphabetically
        Log.d(tag, "Found ${pdfFiles.size} .$fileExt files in the folder")
    }

    private fun openPdf(uri: Uri) {
        val name = getFileName(uri)
        if (name == null) {
            Toast.makeText(this, "Could not get file name", Toast.LENGTH_SHORT).show()
            return
        }
        if (name.endsWith(".pdf", ignoreCase = true)) {
            isImageDoc = false
            loadPDF(uri)
            renderPage(0)
        } else {
            isImageDoc = true
            currentPage?.close()
            currentPage = null
            pdfRenderer?.close()
            pdfRenderer = null
            displayImage(uri, name)
        }
    }

    private fun displayImage(uri: Uri, name: String) {
        try {
            var bitmap = contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            } ?: return
            if (cropMargins) {
                bitmap = cropPrintedBorder(bitmap)
            }
            binding.pdfImageView.setImageBitmap(bitmap)
            binding.pdfImageView.isVisible = true
            binding.receivedPageText.isVisible = false
            binding.bigNumberText.isVisible = false
            binding.readingPageInfo.text = getString(R.string.page_info, name, 1, 1)
            if (isServer) {
                bluetoothController.sendPdfNameViaAdvertisement(name, 0)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error loading image", e)
            Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBLEServer() {
        Log.d(tag, "Starting BLE Server")
        isServer = true
        bluetoothController.setGroupCode(groupCode)
        bluetoothController.startServer()
        midiController.start(groupCode)
        binding.statusText.text = getString(R.string.status_server_started)
        
        if (pdfRenderer != null || isImageDoc) {
            binding.setupControls.isVisible = false
            showReadingControls()
            hideSystemUI()
        }

        Toast.makeText(this, "BLE Server Started", Toast.LENGTH_SHORT).show()
    }

    private fun startBLEClient() {
        // Without a folder the device still works as a section/sheet
        // number display
        if (pdfFiles.isEmpty() && fileExt != TYPE_NUMBERS) {
            Toast.makeText(this, "No folder selected - acting as number display", Toast.LENGTH_LONG).show()
        }
        Log.d(tag, "Starting BLE Client")
        isServer = false
        bluetoothController.setGroupCode(groupCode)
        bluetoothController.startClient()
        midiController.start(groupCode)
        binding.statusText.text = getString(R.string.status_client_started)
        binding.pdfImageView.isVisible = false
        binding.receivedPageText.isVisible = true
        binding.receivedPageText.text = getString(R.string.client_waiting)
        
        binding.setupControls.isVisible = false
        showReadingControls()
        hideSystemUI()

        Toast.makeText(this, "BLE Client Started", Toast.LENGTH_SHORT).show()
    }


    private fun loadPDF(uri: Uri) {
        Log.d(tag, "Loading PDF: $uri")
        try {
            val fileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: return
            currentPage?.close()
            currentPage = null
            pdfRenderer?.close()
            pdfRenderer = PdfRenderer(fileDescriptor)
            Log.d(tag, "PDF loaded: ${pdfRenderer?.pageCount} pages")
        } catch (e: Exception) {
            Log.e(tag, "Error loading PDF", e)
            Toast.makeText(this, "Error loading PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderPage(pageIndex: Int) {
        val renderer = pdfRenderer ?: return
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return

        try {
            currentPage?.close()
            val page = renderer.openPage(pageIndex)
            currentPage = page
            currentPageIndex = pageIndex

            // Render at up to 2x the view width for a crisp image when zoomed
            val viewWidth = binding.root.width.takeIf { it > 0 } ?: 1080
            val scale = (2f * viewWidth / page.width).coerceIn(1f, 4096f / maxOf(page.width, page.height))
            var bitmap = createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val transform = Matrix().apply { setScale(scale, scale) }
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            if (cropMargins) {
                bitmap = cropPrintedBorder(bitmap)
            }

            runOnUiThread {
                binding.pdfImageView.setImageBitmap(bitmap)
                binding.pdfImageView.isVisible = true
                binding.receivedPageText.isVisible = false
                binding.bigNumberText.isVisible = false

                val fileName = currentPdfIndex.takeIf { it >= 0 }?.let { getFileName(pdfFiles[it]) } ?: "Unknown"
                binding.readingPageInfo.text = getString(R.string.page_info, fileName, pageIndex + 1, renderer.pageCount)
                
                if (isServer) {
                    bluetoothController.sendPdfNameViaAdvertisement(fileName, pageIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error rendering page", e)
        }
    }

    // Find the bounding box of non-white content and cut away the empty
    // page margins so the printed area can use the whole screen
    private fun cropPrintedBorder(src: Bitmap): Bitmap {
        val step = maxOf(1, src.width / 300)
        val row = IntArray(src.width)
        var minX = src.width; var minY = src.height; var maxX = -1; var maxY = -1

        for (y in 0 until src.height step step) {
            src.getPixels(row, 0, src.width, 0, y, src.width, 1)
            for (x in 0 until src.width step step) {
                val p = row[x]
                val isContent = (p ushr 24) > 0x80 &&
                        ((p shr 16 and 0xFF) < 235 || (p shr 8 and 0xFF) < 235 || (p and 0xFF) < 235)
                if (isContent) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return src // blank page

        val pad = maxOf(step, src.width / 100)
        minX = maxOf(0, minX - pad)
        minY = maxOf(0, minY - pad)
        maxX = minOf(src.width - 1, maxX + pad)
        maxY = minOf(src.height - 1, maxY + pad)

        val width = maxX - minX + 1
        val height = maxY - minY + 1
        if (width < src.width / 10 || height < src.height / 10) return src
        return Bitmap.createBitmap(src, minX, minY, width, height)
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
    
    private fun showDebugLog() {
        val log = bluetoothController.bleEvents.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("BLE Debug Log")
            .setMessage(log)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestPermissions() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

        val permissionsNotGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNotGranted.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsNotGranted.toTypedArray())
            Log.d(tag, "Requesting permissions: $permissionsNotGranted")
        } else {
            Log.d(tag, "All necessary permissions are already granted")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        currentPage?.close()
        pdfRenderer?.close()
        bluetoothController.stop()
        midiController.stop()
        Log.d(tag, "App destroyed")
    }
}
