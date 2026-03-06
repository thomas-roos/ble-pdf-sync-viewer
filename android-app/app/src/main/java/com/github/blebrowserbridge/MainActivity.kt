package com.github.blebrowserbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.blebrowserbridge.databinding.ActivityMainBinding
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothController: BluetoothController

    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null

    private var pdfFiles: List<Uri> = emptyList()
    private var currentPdfIndex = -1
    private var isServer = false

    private val TAG = "BLE_PDF_SYNC"

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            if (!isGranted) {
                Log.w(TAG, "Permission not granted: $permission")
                Toast.makeText(this, "Permission required: $permission", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                Log.d(TAG, "Folder selected: $uri")
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                listPdfFilesInFolder(uri)
                if (pdfFiles.isNotEmpty()) {
                    currentPdfIndex = 0
                    openPdf(pdfFiles[currentPdfIndex])
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothController = BluetoothController(this)
        // This is the new, smarter client logic
        bluetoothController.onPdfNameReceived = { pdfName ->
            runOnUiThread {
                if (!isServer) {
                    Log.d(TAG, "Client received PDF name: $pdfName")
                    // Match by the start of the name to handle truncated advertisement data
                    val uriToOpen = pdfFiles.find { uri -> getFileName(uri)?.startsWith(pdfName, ignoreCase = true) == true }
                    if (uriToOpen != null) {
                        Log.d(TAG, "Found matching PDF: ${getFileName(uriToOpen)}")
                        currentPdfIndex = pdfFiles.indexOf(uriToOpen)
                        // Always open/reload when a valid update is received from the controller
                        openPdf(uriToOpen)
                    } else {
                        Log.w(TAG, "No local PDF found starting with: $pdfName")
                        binding.pdfImageView.visibility = View.GONE
                        binding.receivedPageText.visibility = View.VISIBLE
                        binding.receivedPageText.text = "Received: '$pdfName' (File not found locally)"
                        Toast.makeText(this, "'$pdfName' not found in your selected folder", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        initBluetooth()
        setupUI()
        requestPermissions()

        Log.d(TAG, "App started")
    }

    private fun initBluetooth() {
        if (!bluetoothController.isBluetoothEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Bluetooth not enabled")
        } else {
            Log.d(TAG, "Bluetooth initialized successfully")
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

        binding.prevButton.visibility = View.VISIBLE
        binding.nextButton.visibility = View.VISIBLE

        binding.prevButton.setOnClickListener {
            if (currentPdfIndex > 0) {
                currentPdfIndex--
                openPdf(pdfFiles[currentPdfIndex])
            } else {
                Toast.makeText(this, "First PDF in folder", Toast.LENGTH_SHORT).show()
            }
        }

        binding.nextButton.setOnClickListener {
            if (currentPdfIndex < pdfFiles.size - 1) {
                currentPdfIndex++
                openPdf(pdfFiles[currentPdfIndex])
            } else {
                Toast.makeText(this, "Last PDF in folder", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.debugButton.setOnClickListener {
            showDebugLog()
        }
    }

    private fun selectPdfFolder() {
        Log.d(TAG, "Selecting PDF folder")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        pickPdfLauncher.launch(intent)
    }

    private fun listPdfFilesInFolder(folderUri: Uri) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri))
        val cursor = contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)

        val pdfs = mutableListOf<Uri>()
        cursor?.use {
            while (it.moveToNext()) {
                val mimeType = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                if (mimeType == "application/pdf") {
                    val docId = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                    pdfs.add(fileUri)
                }
            }
        }
        pdfFiles = pdfs.sortedBy { getFileName(it) } // Sort files alphabetically
        Log.d(TAG, "Found ${pdfFiles.size} PDF files in the folder")
    }

    private fun openPdf(uri: Uri) {
        val pdfName = getFileName(uri)
        if (pdfName != null) {
            // Only the server broadcasts the name
            if (isServer) {
                bluetoothController.sendPdfNameViaAdvertisement(pdfName)
            }
            loadPDF(uri)
            binding.pageInfo.text = pdfName
        } else {
            Toast.makeText(this, "Could not get PDF name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBLEServer() {
        Log.d(TAG, "Starting BLE Server")
        isServer = true
        bluetoothController.startServer()
        binding.statusText.text = "Status: BLE Server Started"
        Toast.makeText(this, "BLE Server Started", Toast.LENGTH_SHORT).show()
    }

    private fun startBLEClient() {
        if (pdfFiles.isEmpty()) {
            Toast.makeText(this, "Please select a PDF folder on this device first", Toast.LENGTH_LONG).show()
            return
        }
        Log.d(TAG, "Starting BLE Client")
        isServer = false
        bluetoothController.startClient()
        binding.statusText.text = "Status: BLE Client Started"
        binding.pdfImageView.visibility = View.GONE
        binding.receivedPageText.visibility = View.VISIBLE
        binding.receivedPageText.text = "Client Mode: Waiting for PDF name..."
        Toast.makeText(this, "BLE Client Started", Toast.LENGTH_SHORT).show()
    }


    private fun loadPDF(uri: Uri) {
        try {
            val fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            fileDescriptor?.let {
                pdfRenderer?.close()
                pdfRenderer = PdfRenderer(it)
                currentPage = pdfRenderer?.openPage(0)
                currentPage?.let { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    binding.pdfImageView.setImageBitmap(bitmap)
                    binding.pdfImageView.visibility = View.VISIBLE
                    binding.receivedPageText.visibility = View.GONE
                }
                Log.d(TAG, "PDF loaded successfully.")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error loading PDF", e)
            Toast.makeText(this, "Error loading PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if(nameIndex != -1) {
                       result = cursor.getString(nameIndex)
                    } 
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                if (result != null) {
                    result = result.substring(cut!! + 1)
                }
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
            Log.d(TAG, "Requesting permissions: $permissionsNotGranted")
        } else {
            Log.d(TAG, "All necessary permissions are already granted")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPage?.close()
        pdfRenderer?.close()
        bluetoothController.stop()
        Log.d(TAG, "App destroyed")
    }
}
