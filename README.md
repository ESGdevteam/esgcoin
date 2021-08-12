# Esgcoin Reference Software (Esgcoin Wallet)

HDD-mined cryptocurrency using an energy efficient
and fair Proof-of-Capacity (PoC) consensus algorithm (BURST based).

This wallet version is developed and maintained by the Esg Development Team (ADT). The two supported database servers are:

- MariaDB (recommended)
- H2 (embedded, easier install)

## Network Features

- Proof of Capacity - ASIC proof / Energy efficient mining
- No ICO/Airdrops/Premine
- Turing-complete smart contracts, via [Automated Transactions (ATs)](https://ciyam.org/at/at.html)
- Asset Exchange, Digital Goods Store, Crowdfunds (via ATs), and Alias system

## Network Specification

- 4 minute block time
- Total Supply: [2,158,812,800 ESG](https://esgwiki.org/en/block-reward/)
- Block reward starts at 10,000/block
- Block Reward Decreases at 5% each month

## ARS Features

- Decentralized Peer-to-Peer network with spam protection
- Built in Java - runs anywhere, from a Raspberry Pi to a Phone
- Fast sync with multithreaded CPU or, optionally, an OpenCL GPU
- HTTP and gRPC API for clients to interact with network

# Installation

## Prerequisites (All Platforms)

**NOTE: `esg.sh` is now deprecated and will not be included with the next release.**

### Java 8 (Required)

You need Java 8 installed. To check if it is, run `java -version`. You should get an output similar to the following:

```text
java version "1.8.0_181"
Java(TM) SE Runtime Environment (build 1.8.0_181-b13)
Java HotSpot(TM) 64-Bit Server VM (build 25.181-b13, mixed mode)
```

The important part is that the Java version starts with `1.8` (Java 8)

If you do not have Java 8 installed, download it from [Oracle's Website](https://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html)

### MariaDB (Optional)

[Download and install MariaDB](https://mariadb.com/downloads/mariadb-tx)

The MariaDb installation will ask to setup a password for the root user. 
Add this password to the `brs.properties` file you will create when installing BRS:

```properties
DB.Url=jdbc:mariadb://localhost:3306/brs_master
DB.Username=root
DB.Password=YOUR_PASSWORD
```

## Installation

You can manually install using the following steps, or by using the pre-packaged options below.

### Manually installing - All Platforms

Grab the latest release (Or, if you prefer, compile yourself using the instructions below)

In the conf directory, copy `brs-default.properties` into a new file named `brs.properties` and modify this file to suit your needs (See "Configuration" section below)

To run ARS, run `java -jar atm.jar`. On MacOS and Windows this will create a tray icon to show that ARS is running. To disable this, instead run `java -jar atm.jar --headless`.

## Configuration

### Running on mainnet (unless you are developing or running on testnet, you will probably want this)

Now you need to add the following to your `conf/brs.properties` (as a minimum):

```properties
DB.Url=jdbc:mariadb://localhost:3306/brs_master
DB.Username=brs_user
DB.Password=yourpassword
```

Once you have done this, look through the existing properties if there is anything you want to change.

### Testnet

Please see the [Wiki article](https://esgwiki.org/en/testnet/) for details on how to setup a testnet node.

### Private Chains

In order to run a private chain, you need the following properties:

```properties
DEV.DB.Url=(Your Database URL)
DEV.DB.Username=(Your Database Username)
DEV.DB.Password=(Your Database Password2)
API.Listen = 0.0.0.0
API.allowed = *
DEV.TestNet = yes
DEV.Offline = yes
DEV.digitalGoodsStore.startBlock = 0
DEV.automatedTransactions.startBlock = 0
DEV.atFixBlock2.startBlock = 0
DEV.atFixBlock3.startBlock = 0
DEV.atFixBlock4.startBlock = 0
DEV.preDymaxion.startBlock = 0
DEV.poc2.startBlock = 0
DEV.rewardRecipient.startBlock = 0
```

Optionally, if you want to be able to forge blocks faster, you can add the following properties:

```properties
DEV.mockMining = true
DEV.mockMining.deadline = 10
```

This will cause a block to be forged every 10 seconds. Note that P2P is disabled when running a private chain and is incompatible with mock mining.

# Building

## Building the latest stable release

Run these commands (`master` is always the latest stable release):

```bash
git fetch --all --tags --prune
git checkout origin/master
mvn package
```

Your packaged release will now be available in `dist/esgcoin-2.4.0.zip`

## Building the latest development version

Run these commands (`develop` is always the latest stable release):

```bash
git fetch --all --tags --prune
git checkout origin/develop
mvn package
```

Your packaged release will now be available in `dist/esgcoin-2.4.0.zip`.

**Please note that development builds will refuse to run outside of testnet or a private chain**
