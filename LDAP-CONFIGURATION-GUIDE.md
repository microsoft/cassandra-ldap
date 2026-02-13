# Enable LDAP Authentication in Azure Managed Instance for Apache Cassandra

> **Based on:** [Official Microsoft documentation](https://learn.microsoft.com/en-us/azure/managed-instance-apache-cassandra/ldap)
> **Updated:** February 12, 2026 — includes corrections and missing steps discovered during hands-on testing.
>
> Each correction is marked with a **📌 CORRECTION** callout explaining what was changed from the original doc and why.

Azure Managed Instance for Apache Cassandra provides automated deployment and scaling operations for managed open-source Apache Cassandra datacenters. This article discusses how to enable Lightweight Directory Access Protocol (LDAP) authentication to your clusters and datacenters.

> [!IMPORTANT]
> LDAP authentication is in public preview. LDAP doesn't support Cassandra v5.0; it is currently in-progress — you can submit your interest by creating a Technical Azure Support ticket. This feature is provided without a service-level agreement. We don't recommend it for production workloads. For more information, see [Supplemental Terms of Use for Microsoft Azure Previews](https://azure.microsoft.com/support/legal/preview-supplemental-terms/).

---

## Prerequisites

- If you don't have an Azure subscription, create a [free account](https://azure.microsoft.com/pricing/purchase-options/azure-account) before you begin.
- An Azure Managed Instance for Apache Cassandra cluster. For more information, see [Create an Azure Managed Instance for Apache Cassandra cluster from the Azure portal](https://learn.microsoft.com/en-us/azure/managed-instance-apache-cassandra/create-cluster-portal).

> 📌 **CORRECTION — VNet requirement (not in original doc):**
> The LDAP server VM **must** be deployed in the same VNet as the Cassandra cluster (or in a peered VNet). The Cassandra managed-instance nodes need private-network connectivity to the LDAP server. This requirement is never stated in the original documentation.

---

## Deploy an LDAP server in Azure

In this section, you create a simple LDAP server on a virtual machine in Azure. If you already have an LDAP server running, you can skip ahead to [Enable LDAP authentication](#enable-ldap-authentication).

### Step 1 — Deploy a virtual machine

> 📌 **CORRECTION — Ubuntu version (original doc says 18.04):**
> The original doc recommends Ubuntu Server 18.04 LTS, which reached end-of-life in April 2023. Use **Ubuntu 22.04 LTS** (or newer) instead.

Deploy a virtual machine in Azure using Ubuntu Server 22.04 LTS. The VM must be in the **same VNet** as your Cassandra cluster, on a **different subnet** (not the Cassandra-delegated subnet).

```bash
az vm create \
  --resource-group <resource-group> \
  --name <vm-name> \
  --image Ubuntu2204 \
  --vnet-name <vnet-name> \
  --subnet <vm-subnet> \
  --admin-username azureuser \
  --generate-ssh-keys \
  --public-ip-address-dns-name <dnsname>
```

### Step 2 — Give your server a DNS name

In the Azure Portal, go to the VM's Public IP resource → **Configuration** → set a **DNS name label**.

This gives you an FQDN like: `<dnsname>.<region>.cloudapp.azure.com`

Also note the VM's **private IP address** — you will need it later:

```bash
az vm show \
  --resource-group <resource-group> \
  --name <vm-name> \
  --show-details \
  --query "privateIps" -o tsv
```

> 📌 **CORRECTION — Private IP vs FQDN (not in original doc):**
> Use the VM's **private IP** (not the FQDN) when configuring `--ldap-server-hostname` in the datacenter update command. Azure public DNS resolves the FQDN to the **public IP**, not the private IP. Since Cassandra nodes communicate within the VNet, using the FQDN can cause TLS SAN mismatches or route traffic through the public IP unnecessarily.

### Step 3 — Install Docker

SSH into the VM and install Docker:

```bash
ssh azureuser@<vm-public-ip>

sudo apt-get update
sudo apt-get install -y docker.io
sudo systemctl enable docker
sudo systemctl start docker
sudo usermod -aG docker azureuser
```

Also install LDAP client tools (needed for testing):

```bash
sudo apt-get install -y ldap-utils
```

### Step 4 — Create the test LDAP user file

In the home directory, create a file that contains a test LDAP user account:

```bash
mkdir -p ~/ldap-user && cat > ~/ldap-user/user.ldif <<'EOL'
dn: uid=admin,dc=example,dc=org
uid: admin
cn: admin
sn: 3
objectClass: top
objectClass: posixAccount
objectClass: inetOrgPerson
loginShell: /bin/bash
homeDirectory: /home/admin
uidNumber: 14583102
gidNumber: 14564100
userPassword: admin
mail: admin@example.com
gecos: admin
EOL
```

### Step 5 — Generate custom TLS certificates

> 📌 **CORRECTION — Expired certificate (critical, not in original doc):**
> The `osixia/openldap:1.5.0` Docker image ships with a baked-in CA certificate from `docker-light-baseimage` that **expired on Jan 15, 2026**. If you follow the original doc (which just copies certs out of the container), LDAPS will fail with TLS handshake errors. You **must** generate your own CA and server certificates and mount them into the container.

```bash
mkdir -p ~/ldap-certs && cd ~/ldap-certs

# 5a. Generate a CA key and self-signed CA certificate
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt \
  -subj '/CN=LDAP-CA/O=MyOrg'

# 5b. Generate a server key and CSR
openssl genrsa -out server.key 4096
openssl req -new -key server.key -out server.csr \
  -subj "/CN=<dnsname>.<region>.cloudapp.azure.com/O=MyOrg"

# 5c. Create a SAN extension config (include BOTH the FQDN and private IP)
cat > ext.cnf <<EOF
[v3_req]
subjectAltName = @alt_names

[alt_names]
DNS.1 = <dnsname>.<region>.cloudapp.azure.com
IP.1  = <private-ip>
EOF

# 5d. Sign the server certificate with the CA
openssl x509 -req -days 730 \
  -in server.csr \
  -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt \
  -extfile ext.cnf -extensions v3_req

# 5e. Verify the SAN is correct
openssl x509 -in server.crt -text -noout | grep -A2 "Subject Alternative Name"
```

You should see both the DNS name and the IP address listed in the Subject Alternative Name.

### Step 6 — Run the OpenLDAP container

> 📌 **CORRECTION — Port publishing (critical, not in original doc):**
> The original doc's `docker run` command is missing `-p 389:389 -p 636:636`. Without these flags, LDAP ports are only accessible **inside** the container — the Cassandra nodes will never be able to reach the LDAP server.

> 📌 **CORRECTION — Region placeholder (original doc hardcodes `uksouth`):**
> The original doc hardcodes `.uksouth.cloudapp.azure.com`. Replace `<region>` with your actual Azure region.

Replace `<dnsname>` and `<region>` with your values:

```bash
sudo docker run -d \
  --name <dnsname> \
  --hostname <dnsname>.<region>.cloudapp.azure.com \
  -p 389:389 \
  -p 636:636 \
  -e LDAP_ORGANISATION='Example Inc' \
  -e LDAP_DOMAIN='example.org' \
  -e LDAP_ADMIN_PASSWORD='admin' \
  -e LDAP_TLS_VERIFY_CLIENT='never' \
  -e LDAP_TLS_CRT_FILENAME=server.crt \
  -e LDAP_TLS_KEY_FILENAME=server.key \
  -e LDAP_TLS_CA_CRT_FILENAME=ca.crt \
  -v ~/ldap-certs/server.crt:/container/service/slapd/assets/certs/server.crt \
  -v ~/ldap-certs/server.key:/container/service/slapd/assets/certs/server.key \
  -v ~/ldap-certs/ca.crt:/container/service/slapd/assets/certs/ca.crt \
  -v ~/ldap-user:/container/service/slapd/assets/test \
  osixia/openldap:1.5.0
```

Verify the container is running and ports are accessible:

```bash
sudo docker ps
nc -zv <private-ip> 636
nc -zv <private-ip> 389
```

### Step 7 — Copy the server certificate for later use

Copy the server certificate (`server.crt`) to your local machine or cloud drive — you will pass it to the `--ldap-server-certs` parameter later:

```bash
# From your local machine:
scp azureuser@<vm-public-ip>:~/ldap-certs/server.crt ./ldap-server.crt

# Or, if using Azure Cloud Shell, copy to clouddrive:
cp ~/ldap-certs/server.crt ~/clouddrive/ldap.crt
```

> 📌 **CORRECTION — Which certificate to provide (ambiguous in original doc):**
> The original doc says to copy `ldap.crt` from the container (which is the auto-generated server cert). Since we now generate custom certs, use the `server.crt` you generated in Step 5. The `--ldap-server-certs` parameter expects the **server certificate** (not the CA cert).

### Step 8 — Verify the certificate

```bash
openssl x509 -in ~/ldap-certs/server.crt -text -noout
```

Confirm that:
- The certificate is **not expired**
- The **Subject Alternative Name** includes your FQDN and/or private IP

### Step 9 — Add the test user to LDAP

```bash
ldapadd -x -H ldap://127.0.0.1:389 \
  -D 'cn=admin,dc=example,dc=org' -w admin \
  -f ~/ldap-user/user.ldif
```

Verify the user was created:

```bash
ldapsearch -x -H ldap://127.0.0.1:389 \
  -b 'dc=example,dc=org' \
  -D 'cn=admin,dc=example,dc=org' -w admin \
  '(cn=admin)' dn uid cn
```

Test that the user can bind over LDAPS:

```bash
ldapwhoami -x -H ldaps://<private-ip>:636 \
  -D 'uid=admin,dc=example,dc=org' -w admin
```

### Step 10 — Configure NSG rules for LDAP ports

> 📌 **CORRECTION — NSG rules (critical, not in original doc):**
> The original doc never mentions Network Security Group rules. Azure VMs get an NSG by default that **blocks** inbound traffic on ports 389 and 636. Without adding explicit allow rules, the Cassandra nodes cannot reach the LDAP server and authentication silently fails.

```bash
az network nsg rule create \
  --resource-group <resource-group> \
  --nsg-name <vm-nsg-name> \
  --name allow-ldaps \
  --priority 110 \
  --direction Inbound \
  --access Allow \
  --protocol Tcp \
  --destination-port-ranges 389 636 \
  --source-address-prefixes '*'
```

Verify the rule:

```bash
az network nsg rule list \
  --resource-group <resource-group> \
  --nsg-name <vm-nsg-name> \
  --query "[?name=='allow-ldaps']" -o table
```

---

## Enable LDAP authentication

> [!IMPORTANT]
> If you skipped the previous section because you already have an LDAP server, be sure that it has server SSL certificates enabled. The `subject alternative name (dns name)` specified for the certificate must also match the domain of the server that LDAP is hosted on, or authentication fails.

### Step 1 — Install the preview CLI extension

Currently, LDAP authentication is a public preview feature. Run the following command to add the required Azure CLI extension:

```bash
az extension add --upgrade --name cosmosdb-preview
```

### Step 2 — Set the authentication method to LDAP

Replace `<resource group>` and `<cluster name>` with the appropriate values:

```bash
az managed-cassandra cluster update \
  -g <resource group> \
  -c <cluster name> \
  --authentication-method "Ldap"
```

Verify:

```bash
az managed-cassandra cluster show \
  -g <resource group> \
  -c <cluster name> \
  --query "properties.authenticationMethod" -o tsv
```

Expected output: `Ldap`

### Step 3 — Set LDAP properties at the datacenter level

> 📌 **CORRECTION — Missing parameters (critical, original doc is incomplete):**
> The original doc's `datacenter update` command is missing two essential parameters:
> - **`--ldap-search-filter "(cn=%s)"`** — Without this, Cassandra doesn't know how to look up a user DN from the username. This is required.
> - **`--ldap-server-port 636`** — The port should be explicitly set to ensure LDAPS (not plaintext LDAP) is used.
>
> The original doc also omits these parameters entirely from its command block.

> 📌 **CORRECTION — Search filter format (`%s` not `{0}`):**
> The search filter placeholder must be `%s` (e.g., `(cn=%s)`), where `%s` is replaced by the username at authentication time. Some CLI help text references `{0}` — this does **not** work.

**Bash / Azure Cloud Shell:**

```bash
az managed-cassandra datacenter update \
  -g <resource group> \
  -c <cluster name> \
  -d <datacenter-name> \
  --ldap-search-base-dn "dc=example,dc=org" \
  --ldap-server-certs "<path-to-server.crt>" \
  --ldap-server-hostname "<ldap-vm-private-ip>" \
  --ldap-server-port 636 \
  --ldap-service-user-dn "cn=admin,dc=example,dc=org" \
  --ldap-svc-user-pwd "admin" \
  --ldap-search-filter "(cn=%s)"
```

> 📌 **CORRECTION — PowerShell escaping (not in original doc):**
> On Windows PowerShell, parentheses in the search filter `(cn=%s)` are silently stripped, which causes the filter to be stored incorrectly (e.g., `cn=%s` without the wrapping parens). You **must** use the `az --%` stop-parsing token:

**PowerShell (Windows):**

```powershell
az --% managed-cassandra datacenter update -g "<resource group>" -c "<cluster name>" -d "<datacenter-name>" --ldap-search-base-dn "dc=example,dc=org" --ldap-server-certs "<path-to-server.crt>" --ldap-server-hostname "<ldap-vm-private-ip>" --ldap-server-port 636 --ldap-service-user-dn "cn=admin,dc=example,dc=org" --ldap-svc-user-pwd "admin" --ldap-search-filter "(cn=%s)"
```

> **Note:** When using `az --%`, you cannot use line-continuation (`\` or `` ` ``). The entire command must be on a single line.

Verify the configuration:

```bash
az managed-cassandra datacenter show \
  -g <resource group> \
  -c <cluster name> \
  -d <datacenter-name> \
  -o json
```

> 📌 **CORRECTION — Masked credentials in API response (not in original doc):**
> The `serviceUserDistinguishedName` and `serviceUserPassword` fields will appear as **empty strings** in the API response. This is expected behavior — the API masks these values for security. They are stored and used correctly by the service. Do not re-run the update command just because these fields look blank.

### Step 4 — Wait for config propagation

> 📌 **CORRECTION — Propagation delay (not in original doc):**
> The original doc says "After this command finishes, you should be able to use CQLSH…" — this is **misleading**. In our testing, the `datacenter update` command returned `provisioningState: Succeeded` within minutes, but actual LDAP authentication did not work until **several hours later**. The managed service performs a rolling config update across all nodes, which can take significant time.
>
> If authentication fails with "Bad credentials" immediately after the update succeeds, **wait and retry periodically** — do not assume the configuration is wrong.

Check provisioning state:

```bash
az managed-cassandra datacenter show \
  -g <resource group> \
  -c <cluster name> \
  -d <datacenter-name> \
  --query "properties.provisioningState" -o tsv
```

### Step 5 — Install cqlsh and configure SSL

> 📌 **CORRECTION — cqlsh installation (not in original doc):**
> The original doc assumes cqlsh is available but doesn't show how to install it.

SSH into the LDAP VM (or any machine with network access to the Cassandra nodes):

```bash
pip3 install cqlsh
```

Configure SSL for connecting to the managed instance:

```bash
mkdir -p ~/.cassandra

cat > ~/.cassandra/cqlshrc <<'EOF'
[connection]
ssl = true

[ssl]
validate = false
EOF
```

> 📌 **CORRECTION — cqlshrc configuration (not in original doc):**
> The original doc shows `export SSL_VALIDATE=false`, but creating a `~/.cassandra/cqlshrc` file with the `[ssl]` section is the more reliable approach that persists across sessions.

> 📌 **CORRECTION — SSL cipher incompatibility on Ubuntu 22.04+ (critical, not in original doc):**
> Azure Managed Cassandra nodes negotiate the `AES256-SHA` TLS cipher. On **Ubuntu 22.04** and later, OpenSSL 3.0 defaults to `SECLEVEL=2`, which blocks this cipher. This causes `SSLV3_ALERT_HANDSHAKE_FAILURE` errors from cqlsh even though the nodes are healthy.
>
> This is a **client-side** issue — it cannot be fixed in the server-side JAR. You must patch cqlsh's SSL handling.
>
> **Fix:** Patch the `sslhandling.py` file in the `cqlshlib` package to include `SECLEVEL=1` ciphers:
>
> ```bash
> # Find the file
> SSL_FILE=$(python3 -c "import cqlshlib.sslhandling; print(cqlshlib.sslhandling.__file__)")
>
> # Backup
> cp "$SSL_FILE" "${SSL_FILE}.bak"
>
> # Patch: add ciphers to the ssl_settings() return dict
> sed -i "s/return dict(ca_certs=ssl_certfile,/return dict(ca_certs=ssl_certfile, ciphers='DEFAULT:@SECLEVEL=1',/" "$SSL_FILE"
>
> # Verify
> grep "return dict" "$SSL_FILE"
> ```
>
> If you are using the **Python cassandra-driver directly** (not cqlsh), pass the cipher config in your SSL context:
>
> ```python
> import ssl
> ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
> ctx.check_hostname = False
> ctx.verify_mode = ssl.CERT_NONE
> ctx.set_ciphers('DEFAULT:@SECLEVEL=1')
>
> cluster = Cluster(['<node-ip>'], port=9042, auth_provider=auth, ssl_context=ctx)
> ```
>
> **Note:** This fix must be re-applied after upgrading or reinstalling cqlsh (`pip3 install --upgrade cqlsh`).

### Step 6 — Test LDAP authentication

After the configuration has finished propagating, connect using the LDAP user:

```bash
~/.local/bin/cqlsh <data-node-ip> 9042 -u admin -p admin --ssl -e 'DESCRIBE KEYSPACES;'
```

Expected output:

```
system       system_distributed  system_traces  system_virtual_schema
system_auth  system_schema       system_views
```

Test with **wrong credentials** to confirm LDAP validation is working:

```bash
# Wrong password — should fail
~/.local/bin/cqlsh <data-node-ip> 9042 -u admin -p wrongpassword --ssl -e 'DESCRIBE KEYSPACES;'

# Non-existent user — should fail
~/.local/bin/cqlsh <data-node-ip> 9042 -u fakeuser -p fakepass --ssl -e 'DESCRIBE KEYSPACES;'
```

Both commands should return:

```
AuthenticationFailed: ... Bad credentials ...
LDAPAuthFailedException: Not possible to login <user>
```

Test on **all Cassandra nodes** to confirm cluster-wide propagation:

```bash
for NODE_IP in <node1-ip> <node2-ip> <node3-ip>; do
  echo "--- Testing node $NODE_IP ---"
  ~/.local/bin/cqlsh $NODE_IP 9042 -u admin -p admin --ssl -e 'DESCRIBE KEYSPACES;' 2>&1
done
```

---

## Troubleshooting

### Common issues

| Symptom | Cause | Fix |
|---|---|---|
| `Bad credentials` immediately after datacenter update | Config propagation delay | Wait several hours and retry periodically |
| `Connection refused` on port 636 from Cassandra nodes | Docker container missing `-p` port flags | Recreate container with `-p 389:389 -p 636:636` |
| `Connection timed out` on port 636 from Cassandra nodes | NSG blocks LDAP ports | Add NSG inbound rule for TCP 389 + 636 (see Step 10 above) |
| TLS handshake error / SSL error | Expired certificate in Docker image | Generate custom certs and mount them (see Step 5 above) |
| Search filter stored without closing `)` | PowerShell strips parentheses | Use `az --%` stop-parsing token (see Step 3 above) |
| `serviceUserDistinguishedName` shows empty in API | Normal — API masks credentials | Credentials are stored correctly; ignore the blank display |
| Auth works on some nodes but not others | Rolling config propagation still in progress | Wait for all nodes to receive the update |
| `SSLV3_ALERT_HANDSHAKE_FAILURE` from cqlsh | OpenSSL 3.0 (Ubuntu 22.04+) blocks `AES256-SHA` cipher at `SECLEVEL=2` | Patch `cqlshlib/sslhandling.py` to add `ciphers='DEFAULT:@SECLEVEL=1'` (see Step 5) |

### Diagnostic commands

```bash
# Check LDAP container is running
sudo docker ps

# Check LDAP port connectivity
nc -zv <private-ip> 636
nc -zv <private-ip> 389

# Verify LDAP user exists
ldapsearch -x -H ldap://127.0.0.1:389 \
  -b 'dc=example,dc=org' \
  -D 'cn=admin,dc=example,dc=org' -w admin \
  '(cn=admin)' dn uid cn

# Check TLS certificate expiry
echo | openssl s_client -connect <private-ip>:636 2>/dev/null | openssl x509 -noout -dates

# Check datacenter LDAP config
az managed-cassandra datacenter show \
  -g <resource group> -c <cluster name> -d <datacenter-name> -o json \
  | grep -E "ldap|search|service|hostname|provision"

# Check cluster auth method
az managed-cassandra cluster show \
  -g <resource group> -c <cluster name> \
  --query "properties.authenticationMethod" -o tsv

# View OpenLDAP container logs
sudo docker logs <container-name> --tail 50
```

---

## Summary of corrections from the original documentation

| # | Severity | Original Doc Issue | Correction Applied |
|---|---|---|---|
| 1 | 🔴 Critical | `docker run` missing `-p 389:389 -p 636:636` | Added port-publishing flags |
| 2 | 🔴 Critical | CA cert in `osixia/openldap:1.5.0` expired Jan 15 2026 | Added full custom cert generation steps |
| 3 | 🔴 Critical | `datacenter update` missing `--ldap-search-filter` | Added `--ldap-search-filter "(cn=%s)"` |
| 4 | 🔴 Critical | `datacenter update` missing `--ldap-server-port` | Added `--ldap-server-port 636` |
| 5 | 🔴 Critical | No mention of NSG rules for LDAP ports | Added NSG rule creation step |
| 6 | 🟡 Important | Recommends Ubuntu 18.04 (EOL since Apr 2023) | Changed to Ubuntu 22.04 |
| 7 | 🟡 Important | Says auth works immediately after update | Added propagation delay warning |
| 8 | 🟡 Important | Search filter placeholder format undocumented | Documented `%s` format (not `{0}`) |
| 9 | 🟡 Important | Uses FQDN for `--ldap-server-hostname` | Recommends private IP instead |
| 10 | 🟡 Important | No PowerShell guidance | Added `az --%` stop-parsing instructions |
| 11 | 🟡 Important | Region hardcoded to `uksouth` | Changed to `<region>` placeholder |
| 12 | 🟢 Minor | No cqlsh installation instructions | Added `pip3 install cqlsh` step |
| 13 | 🟢 Minor | No `cqlshrc` SSL configuration | Added `~/.cassandra/cqlshrc` setup |
| 14 | 🟢 Minor | Masked credentials not explained | Added note about empty fields in API response |
| 15 | 🟢 Minor | VNet requirement not stated | Added note about same-VNet requirement |
| 16 | 🟢 Minor | Ambiguous which cert to provide | Clarified: provide the server certificate |
| 17 | 🔴 Critical | cqlsh on Ubuntu 22.04+ fails with `SSLV3_ALERT_HANDSHAKE_FAILURE` | Added `cqlshlib/sslhandling.py` patch to set `ciphers='DEFAULT:@SECLEVEL=1'` |

---

## Related content

- [LDAP authentication with Microsoft Entra ID](https://learn.microsoft.com/en-us/azure/active-directory/fundamentals/auth-ldap)
- [Manage Azure Managed Instance for Apache Cassandra resources by using the Azure CLI](https://learn.microsoft.com/en-us/azure/managed-instance-apache-cassandra/manage-resources-cli)
- [Deploy a Managed Apache Spark Cluster with Azure Databricks](https://learn.microsoft.com/en-us/azure/managed-instance-apache-cassandra/deploy-cluster-databricks)
