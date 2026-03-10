import SwiftUI

@main
struct FindMeSpikeApp: App {
    // We create the manager as singleton for the entire app
    @StateObject private var locationManager = FindMeLocationManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(locationManager)
        }
    }
}