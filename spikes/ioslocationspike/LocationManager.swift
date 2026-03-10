import Foundation
import CoreLocation
import os.log

class FindMeLocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let locationManager = CLLocationManager()
    let logger = OSLog(subsystem: "com.findme.app", category: "LocationSpike")
    
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var locationHistory: [String] = [] // For the UI
    
    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = true 
        locationManager.pausesLocationUpdatesAutomatically = false
    }
    
    func requestPermissionsAndStart() {
        locationManager.requestAlwaysAuthorization()
    }
    
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // Update UI (must happen on the main thread)
        DispatchQueue.main.async {
            self.authorizationStatus = manager.authorizationStatus
        }
        
        if manager.authorizationStatus == .authorizedAlways {
            os_log("Starting Significant Change Service.", log: self.logger, type: .info)
            locationManager.startMonitoringSignificantLocationChanges()
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let latestLocation = locations.last else { return }
        
        let lat = latestLocation.coordinate.latitude
        let lon = latestLocation.coordinate.longitude
        let timestamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        
        let logMessage = "[\(timestamp)] Lat: \(lat), Lon: \(lon)"
        os_log("%{public}@", log: self.logger, type: .info, logMessage)
        
        DispatchQueue.main.async {
            self.locationHistory.insert(logMessage, at: 0) // Neueste oben
        }
        
        // Hier folgt später der HTTP-Call an Rust
    }
}