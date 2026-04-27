package net.vaier.adapter.driven;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.model.CityResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.port.ForGeolocatingIps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Optional;

@Component
@Slf4j
public class DbIpGeolocationAdapter implements ForGeolocatingIps {

    @Value("${geoip.db.path:/geoip/dbip-city-lite.mmdb}")
    private String dbPath;

    private DatabaseReader reader;

    public void setDbPath(String dbPath) {
        this.dbPath = dbPath;
    }

    @PostConstruct
    public void init() {
        File db = new File(dbPath);
        if (!db.exists() || !db.isFile()) {
            log.warn("Geolocation DB not found at {} — peer map will be empty until the geoip-init container populates it.", dbPath);
            reader = null;
            return;
        }
        try {
            reader = new DatabaseReader.Builder(db).build();
            log.info("Loaded geolocation DB from {}", dbPath);
        } catch (Exception e) {
            log.warn("Failed to load geolocation DB at {}: {}", dbPath, e.getMessage());
            reader = null;
        }
    }

    @PreDestroy
    public void cleanup() {
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception e) {
                log.debug("Error closing geolocation DB reader: {}", e.getMessage());
            }
        }
    }

    @Override
    public Optional<GeoLocation> locate(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) return Optional.empty();

        InetAddress addr;
        try {
            addr = InetAddress.getByName(ipAddress.trim());
        } catch (Exception e) {
            return Optional.empty();
        }

        if (isPrivateOrSpecial(addr)) return Optional.empty();
        if (reader == null) return Optional.empty();

        try {
            CityResponse city = reader.city(addr);
            Double lat = city.getLocation() != null ? city.getLocation().getLatitude() : null;
            Double lon = city.getLocation() != null ? city.getLocation().getLongitude() : null;
            if (lat == null || lon == null) return Optional.empty();

            String cityName = city.getCity() != null ? city.getCity().getName() : null;
            String countryName = city.getCountry() != null ? city.getCountry().getName() : null;
            return Optional.of(new GeoLocation(lat, lon, cityName, countryName));
        } catch (AddressNotFoundException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Geolocation lookup failed for {}: {}", ipAddress, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isPrivateOrSpecial(InetAddress addr) {
        if (addr.isLoopbackAddress()
            || addr.isAnyLocalAddress()
            || addr.isLinkLocalAddress()
            || addr.isMulticastAddress()
            || addr.isSiteLocalAddress()) return true;

        if (addr instanceof Inet4Address) {
            byte[] b = addr.getAddress();
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // RFC 6598 CGNAT: 100.64.0.0/10
            if (first == 100 && second >= 64 && second <= 127) return true;
        } else if (addr instanceof Inet6Address) {
            byte[] b = addr.getAddress();
            // IPv6 unique-local fc00::/7 — first 7 bits are 1111110
            if ((b[0] & 0xfe) == 0xfc) return true;
        }
        return false;
    }
}
