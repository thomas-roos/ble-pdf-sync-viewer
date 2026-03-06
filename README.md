# PDF Sync Viewer

This project enables synchronized PDF viewing between Android devices first using BLE communication. But also thinking of RTPMIDI or other options.

## 🚀 Quick Start

### Installation
1. Download the latest APK from [Releases](../../releases)
2. Install on your Android devices
3. Grant Bluetooth and storage permissions when prompted

### Usage
1. **Server Device (Presenter)**:
   - Tap "Select PDF" to choose a PDF file
   - Tap "Start Server" to begin broadcasting
   - Navigate through pages - changes will be broadcast to clients

2. **Client Device (Viewer)**:
   - Tap "Start Client" to scan for servers
   - PDF page changes will be received automatically
   - Load the same PDF file to see synchronized navigation

## 📱 Features

- **Real-time PDF Sync**: Page changes broadcast via BLE
- **Dual Mode**: Server (broadcaster) and Client (receiver) modes
- **No Internet Required**: Direct device-to-device communication
- **PDF Viewer**: Built-in PDF viewing with navigation controls
- **Easy Setup**: Simple one-tap server/client switching

## 🛠️ Development

### Build Android App
```bash
cd android-app
./gradlew assembleDebug
```

## 🔧 Technical Details

- **Android**: Kotlin, BLE Peripheral/Central modes, GATT Server/Client
- **PDF Viewer**: Android PDF Viewer library
- **Communication**: Custom GATT service for page synchronization
- **Range**: ~10-30 meters (typical BLE range)

## 📋 Requirements

### Android
- Android 5.0+ (API 21+)
- Bluetooth LE support
- Storage permission (for PDF file access)
- Location permission (required for BLE advertising/scanning)

## 🚀 CI/CD

This project includes automated workflows:
- **Android APK Build**: Automatic APK generation on releases
- **Release Management**: Tagged releases with downloadable APKs

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## 📄 License

MIT License - see [LICENSE](LICENSE) for details.

## 🐛 Troubleshooting

### Common Issues
- **"No PDF loaded"**: Select a PDF file first before starting server/client
- **"Connection failed"**: Check Bluetooth is enabled on both devices
- **"Permission denied"**: Grant Bluetooth and storage permissions
- **"Server not found"**: Ensure server device is broadcasting

### Debug Tips
- Check that both devices have the same PDF file
- Ensure devices are within BLE range (~10-30m)
- Restart the app if connection issues persist
- Use Android Studio logcat for detailed debugging

## 📖 How It Works

1. **Server Mode**: Device broadcasts page changes via BLE advertisements and GATT server
2. **Client Mode**: Device scans for BLE advertisements and connects to receive page updates
3. **Synchronization**: When server changes pages, all connected clients automatically navigate to the same page
4. **PDF Loading**: Each device loads its own copy of the PDF file for viewing
