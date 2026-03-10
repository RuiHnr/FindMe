import SwiftUI

struct ContentView: View {
    @EnvironmentObject var locationManager: FindMeLocationManager
    
    var body: some View {
        VStack(spacing: 20) {
            Text("FindMe: iOS Spike")
                .font(.largeTitle)
                .bold()
            
            if locationManager.authorizationStatus == .notDetermined {
                Button("Request Location Permission") {
                    locationManager.requestPermissionsAndStart()
                }
                .buttonStyle(.borderedProminent)
            } else {
                Text("Status: \(authorizationStatusText)")
                    .foregroundColor(locationManager.authorizationStatus == .authorizedAlways ? .green : .red)
            }
            
            Divider()
            
            Text("Last Locations:")
                .font(.headline)
            
            List(locationManager.locationHistory, id: \.self) { location in
                Text(location)
                    .font(.caption)
            }
        }
        .padding()
    }
    
    // Helper for readble status
    var authorizationStatusText: String {
        switch locationManager.authorizationStatus {
        case .authorizedAlways: return "Always (Perfect for Background)"
        case .authorizedWhenInUse: return "When in Use (NOT enough)"
        case .denied: return "Denied"
        default: return "Unknown"
        }
    }
}