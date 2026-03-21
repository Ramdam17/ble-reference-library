import SwiftUI

enum ChartExporter {

    @MainActor
    static func exportPNG<V: View>(
        _ view: V,
        filename: String,
        size: CGSize = CGSize(width: 1200, height: 600)
    ) -> URL? {
        let renderer = ImageRenderer(content: view.frame(width: size.width, height: size.height))
        renderer.scale = 2.0

        guard let image = renderer.cgImage else { return nil }

        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let url = docs.appendingPathComponent(filename)

        #if os(iOS)
        guard let data = UIImage(cgImage: image).pngData() else { return nil }
        #else
        let nsImage = NSImage(cgImage: image, size: NSSize(width: size.width, height: size.height))
        guard let tiffData = nsImage.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: tiffData),
              let data = bitmap.representation(using: .png, properties: [:]) else { return nil }
        #endif

        do {
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }
}
