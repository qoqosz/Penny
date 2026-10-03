import AppKit
import Foundation

setvbuf(stdout, nil, _IOLBF, 0)

let arguments = Array(CommandLine.arguments.dropFirst())

do {
    switch arguments.first ?? "serve" {
    case "serve": try Commands.serve()
    case "pair": try Commands.pair()
    case "doctor": try Commands.doctor(verbose: arguments.contains("--verbose"))
    case "install": try Commands.install()
    case "uninstall": try Commands.uninstall()
    case "devices": Commands.devices()
    case "revoke": try Commands.revoke(arguments.dropFirst().joined(separator: " "))
    case "selftest": try SelfTest.run()
    case "demo":
        guard let dir = arguments.dropFirst().first else {
            Commands.help()
            exit(2)
        }
        try DemoData.create(at: URL(fileURLWithPath: dir))
    case "help", "-h", "--help": Commands.help()
    default:
        Commands.help()
        exit(2)
    }
} catch {
    Log.error("\(error)")
    exit(1)
}
