# Vaier

<p align="center">
  <strong>Effortless WireGuard mesh networking</strong>
</p>

<p align="center">
  Self-hosted infrastructure management platform with service discovery, DNS integration, and reverse proxy configuration.
</p>

---

## ✨ Features

- 🔍 **Service Discovery** - Automatically discover Docker containers with exposed port mapping
- 🌐 **DNS Management** - Full AWS Route53 management including zones and records via REST API
- 🔀 **Reverse Proxy Integration** - Manage Traefik routes with dynamic configuration file generation
- 🏗️ **Clean Architecture** - Built with hexagonal architecture principles for maintainability
- 🐳 **Docker Ready** - Complete containerized deployment with WireGuard, Traefik, and the management application
- 📚 **OpenAPI Documentation** - Interactive API documentation with Swagger UI

## 🚀 Getting Started

### Prerequisites

- Docker and Docker Compose
- Java 21 (for local development)
- Maven 3.9+ (for local development)
- AWS credentials with Route53 access (for DNS features)
- Domain name for WireGuard and HTTPS access (recommended)

### Running with Docker (Recommended)

1. **Clone the repository**
   ```bash
   git clone https://github.com/geireilertsen/vaier
   cd vaier
   ```

2. **Configure environment variables**

   Create a `.env` file with your configuration:
   ```bash
   VAIER_AWS_KEY=your_aws_access_key
   VAIER_AWS_SECRET=your_aws_secret_key
   ACME_EMAIL=your_email@example.com
   ```

3. **Start the application**
   ```bash
   docker-compose up -d
   ```

4. **Access the application**
   - API: http://localhost:8888
   - Swagger UI: http://localhost:8888/swagger-ui.html
   - Hosted Services Dashboard: http://localhost:8888/hosted-services.html
   - Traefik Dashboard: http://localhost:8080

### Environment Variables

The application uses the following environment variables:

| Variable | Required | Description |
|----------|----------|-------------|
| `VAIER_AWS_KEY` | Yes | AWS access key for Route53 operations |
| `VAIER_AWS_SECRET` | Yes | AWS secret key for Route53 operations |
| `ACME_EMAIL` | Yes | Email for Let's Encrypt certificate notifications |
| `WIREGUARD_CONFIG_PATH` | No | Path to WireGuard config directory (default: `/wireguard/config`) |
| `WIREGUARD_CONTAINER_NAME` | No | Name of WireGuard container (default: `wireguard`) |
| `TRAEFIK_CONFIG_PATH` | No | Path to Traefik config directory (default: `/traefik/config`) |
| `TRAEFIK_API_URL` | No | Traefik API URL (default: `http://traefik:8080`) |

### Running Locally (Development)

1. **Clone the repository**
   ```bash
   git clone https://github.com/geireilertsen/vaier
   cd vaier
   ```

2. **Set environment variables**
   ```bash
   export VAIER_AWS_KEY=your_aws_access_key
   export VAIER_AWS_SECRET=your_aws_secret_key
   ```

3. **Build and run**
   ```bash
   mvn clean package
   java -jar target/vaier-1.0.0-SNAPSHOT.jar
   ```

   Or run directly with Maven:
   ```bash
   mvn spring-boot:run
   ```

4. **Access the application**
   - API: http://localhost:8080
   - Swagger UI: http://localhost:8080/swagger-ui.html
   - Hosted Services Dashboard: http://localhost:8080/hosted-services.html

> **Note:** When running locally, you'll need to adjust the paths for accessing WireGuard and Traefik configurations, or run the full docker-compose stack for complete functionality.

# Server Setup Guide

## Prerequisites

- Ubuntu EC2 t3.small (or larger)
- Port 9443 open in AWS Security Group
- Elastic IP assigned to the instance

---

## 1. System Update
```bash
sudo apt update && sudo apt upgrade -y
```

---

## 2. Install Docker
```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
newgrp docker
```

Verify the installation:
```bash
docker --version
docker compose version
```

---

## 3. Add Swap Space

Recommended for small instances to prevent memory pressure:
```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab
```

---

## 4. Install Portainer

Portainer provides a web UI for managing Docker containers and Compose stacks.
```bash
docker volume create portainer_data

docker run -d \
  --name portainer \
  --restart=always \
  -p 9443:9443 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v portainer_data:/data \
  portainer/portainer-ce:latest
```

Access Portainer at `https://<your-ec2-ip>:9443` and create your admin user on first login.

> **Note:** Portainer uses a self-signed certificate by default. Your browser will show a security warning — this is expected. Accept and proceed.

---

## 5. Deploy Vaier

In Portainer, navigate to **Stacks → Add Stack**, paste the contents of `docker-compose.yml`, configure environment variables (AWS credentials and ACME email), and click **Deploy**.

Alternatively, deploy from the command line:
```bash
git clone https://github.com/geireilertsen/vaier.git
cd vaier
# Create .env file with your credentials
docker compose up -d
```

---

## AWS Security Group — Required Ports

| Port | Protocol | Description          |
|------|----------|----------------------|
| 22   | TCP      | SSH                  |
| 9443 | TCP      | Portainer UI (HTTPS) |
| 51820| UDP      | WireGuard            |
| 80   | TCP      | HTTP (Traefik)       |
| 443  | TCP      | HTTPS (Traefik)      |
| 8888 | TCP      | Vaier API (optional, if not using Traefik) |

---

## 📡 API Endpoints

### DNS Management
- `GET /dns/zones` - List all DNS zones
- `POST /dns/zones` - Create a new DNS zone
- `DELETE /dns/zones/{zoneName}` - Delete a DNS zone
- `GET /dns/zones/{zoneName}/records` - List DNS records for a zone
- `POST /dns/zones/{zoneName}/records` - Add a DNS record to a zone
- `DELETE /dns/zones/{zoneName}/records` - Delete a DNS record from a zone

### Service Discovery
- `GET /hosted-services/discover` - Discover hosted services from Docker containers and Traefik routes
- `GET /docker-services?address={address}&port={port}&tlsEnabled={true|false}` - Query Docker services from a specific server

### Reverse Proxy Management
- `GET /reverse-proxy/routes` - List all configured reverse proxy routes
- `POST /reverse-proxy/routes` - Add a reverse proxy route to Traefik
- `DELETE /reverse-proxy/routes/{dnsName}` - Delete a reverse proxy route

## 🏗️ Architecture

### Application Architecture
Vaier is built using hexagonal architecture with clear separation between:
- **Domain Layer** - Core business logic and entities
- **Application Layer** - Use cases and orchestration
- **Infrastructure Layer** - Adapters for AWS Route53, Docker, Traefik, and WireGuard
- **Web Layer** - REST API with OpenAPI documentation

### Docker Stack Components
The `docker-compose.yml` file deploys three interconnected services:

1. **WireGuard** - VPN server using linuxserver/wireguard
   - Listens on UDP port 51820
   - Configuration stored in `./wireguard/config`
   - Provides secure network access to hosted services

2. **Traefik** - Reverse proxy with automatic HTTPS
   - HTTP (port 80) and HTTPS (port 443) entry points
   - Dashboard on port 8080
   - Let's Encrypt integration for automatic SSL certificates
   - Dynamic configuration from `./traefik/config`

3. **Vaier** - Management application
   - REST API on port 8888 (8080 internally)
   - Accesses WireGuard config directory (read-only)
   - Manages Traefik configuration files
   - Connects to Docker socket for service discovery
   - Accessible via HTTPS at configured domain

## 🛠️ Technology Stack

**Backend:**
- Java 21 + Spring Boot 3.5.5
- AWS SDK for Route53 (2.23.9)
- Docker Java Client (3.3.4)
- Springdoc OpenAPI (2.7.0)
- SnakeYAML for configuration parsing
- Project Lombok for code generation

**Infrastructure:**
- Docker Compose
- Maven 3.9+
- WireGuard container (linuxserver/wireguard)
- Traefik reverse proxy with Let's Encrypt

## 📋 Roadmap

- [x] Project setup and Docker containerization
- [x] AWS Route53 DNS zone and record management
- [x] Traefik reverse proxy route management
- [x] Docker container discovery
- [x] Hosted service discovery with unified REST API
- [x] Reverse proxy route persistence via Traefik file provider
- [x] Web dashboard for hosted services
- [ ] WireGuard peer management (create, list, configure)
- [ ] Automatic key generation and IP allocation
- [ ] Client configuration generation with split-tunneling support
- [ ] Peer deletion and modification
- [ ] WireGuard mesh topology generator
- [ ] Automated DNS record creation for new services
- [ ] Site-to-site routing configuration
- [ ] Enhanced monitoring and management dashboard

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 👨‍💻 Author

Created by [Geir Eilertsen](https://github.com/geireilertsen)

---

<p align="center">
  Made with ❤️ for the self-hosted community
</p>
