import Flutter
import UIKit

public class SwiftShareWhatsappPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "share_whatsapp", binaryMessenger: registrar.messenger())
        let instance = SwiftShareWhatsappPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "installed":
            self.isInstalled(call.arguments, result: result)
        case "share":
            self.share(call.arguments, result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func isInstalled(_ arguments: Any?, result: @escaping FlutterResult) {
        let packageName = (arguments as? String) ?? "com.whatsapp"
        let scheme = packageName.contains("w4b") ? "whatsapp-biz://" : "whatsapp://"

        if let url = URL(string: scheme) {
            result(UIApplication.shared.canOpenURL(url) ? 1 : 0)
        } else {
            result(0)
        }
    }

    private func share(_ arguments: Any?, result: @escaping FlutterResult) {
        guard let dict = arguments as? [String: Any?] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Arguments must be a Map", details: nil))
            return
        }

        let rawPhone = dict["phone"] as? String
        let text = dict["text"] as? String
        let filePath = dict["file"] as? String
        let packageName = (dict["packageName"] as? String) ?? "com.whatsapp"

        // Bersihkan nomor telepon (hanya digit)
        let phone = rawPhone?.replacingOccurrences(of: "[^0-9]", with: "", options: .regularExpression)

        // 1. Direct Chat ke Nomor Telepon via URL Scheme (Tanpa File)
        if let phone = phone, !phone.isEmpty, filePath == nil || filePath?.isEmpty == true {
            let encodedText = (text ?? "").addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            let scheme = packageName.contains("w4b") ? "whatsapp-biz" : "whatsapp"
            let urlString = "\(scheme)://send?phone=\(phone)&text=\(encodedText)"

            if let url = URL(string: urlString), UIApplication.shared.canOpenURL(url) {
                UIApplication.shared.open(url, options: [:]) { success in
                    result(success ? 1 : 0)
                }
                return
            }
        }

        // 2. Share Teks & File via UIActivityViewController
        var activityItems = [Any]()
        let hasFile = !(filePath?.isEmpty ?? true)

        if let text = text, !text.isEmpty {
            if hasFile {
                // Hanya gunakan ItemSource jika menyertakan file agar tidak bentrok di Share Extension WhatsApp
                activityItems.append(OptionalTextActivityItemSource(text: text))
            } else {
                activityItems.append(text)
            }
        }

        if let filePath = filePath, !filePath.isEmpty {
            let fileUrl = URL(fileURLWithPath: filePath)
            activityItems.append(fileUrl)
        }

        if activityItems.isEmpty {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Either file, text, or phone must be provided", details: nil))
            return
        }

        let activityViewController = UIActivityViewController(activityItems: activityItems, applicationActivities: nil)

        // iPad Support
        if UIDevice.current.userInterfaceIdiom == .pad {
            if let topVC = UIApplication.topViewController() {
                activityViewController.popoverPresentationController?.sourceView = topVC.view
                activityViewController.popoverPresentationController?.permittedArrowDirections = []
                activityViewController.popoverPresentationController?.sourceRect = CGRect(x: topVC.view.bounds.midX, y: topVC.view.bounds.midY, width: 0, height: 0)
            }
        }

        activityViewController.excludedActivityTypes = [
            .postToFacebook, .postToTwitter, .postToWeibo, .message,
            .print, .copyToPasteboard, .assignToContact, .saveToCameraRoll,
            .addToReadingList, .postToFlickr, .postToVimeo, .postToTencentWeibo,
            .airDrop, .mail
        ]

        DispatchQueue.main.async {
            self.presentActivityView(activityViewController: activityViewController)
            result(1)
        }
    }

    private func presentActivityView(activityViewController: UIActivityViewController) {
        let fakeViewController = TransparentViewController()
        fakeViewController.modalPresentationStyle = .overFullScreen

        activityViewController.completionWithItemsHandler = { [weak fakeViewController] _, _, _, _ in
            if let presenting = fakeViewController?.presentingViewController {
                presenting.dismiss(animated: false, completion: nil)
            } else {
                fakeViewController?.dismiss(animated: false, completion: nil)
            }
        }

        if let topVC = UIApplication.topViewController() {
            topVC.present(fakeViewController, animated: true) { [weak fakeViewController] in
                fakeViewController?.present(activityViewController, animated: true, completion: nil)
            }
        }
    }
}

// Helper untuk mencari Top ViewController yang kompatibel iOS 13+
extension UIApplication {
    class func topViewController(controller: UIViewController? = nil) -> UIViewController? {
        var root = controller

        if root == nil {
            if #available(iOS 13.0, *) {
                root = UIApplication.shared.connectedScenes
                    .compactMap { $0 as? UIWindowScene }
                    .flatMap { $0.windows }
                    .first { $0.isKeyWindow }?.rootViewController
            } else {
                root = UIApplication.shared.keyWindow?.rootViewController
            }
        }

        if let navigationController = root as? UINavigationController {
            return topViewController(controller: navigationController.visibleViewController)
        }
        if let tabController = root as? UITabBarController {
            if let selected = tabController.selectedViewController {
                return topViewController(controller: selected)
            }
        }
        if let presented = root?.presentedViewController {
            return topViewController(controller: presented)
        }
        return root
    }
}

class TransparentViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.clear
        view.isOpaque = false
    }
}

class OptionalTextActivityItemSource: NSObject, UIActivityItemSource {
    let text: String

    init(text: String) {
        self.text = text
        super.init()
    }

    func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any {
        return text
    }

    func activityViewController(_ activityViewController: UIActivityViewController, itemForActivityType activityType: UIActivity.ActivityType?) -> Any? {
        // WhatsApp iOS tidak mendukung pengiriman teks caption bersamaan dengan file melalui Share Sheet
        if activityType?.rawValue == "net.whatsapp.WhatsApp.ShareExtension" {
            return nil
        }
        return text
    }
}
