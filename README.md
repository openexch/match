# match-main

A simple matching engine for a cryptocurrency exchange. It supports different order types like Limit, Market, and Limit-Maker orders.

## Getting Started

These instructions will get you a copy of the project up and running on your local machine for development and testing purposes.

### Prerequisites

*   Java Development Kit (JDK) 11 or higher
*   Maven

### Installation

Clone the repository and install the dependencies.

```bash
git clone https://github.com/<your-username>/match-java.git
cd match-java
mvn clean install
```

## Usage

The main class is `com.match.Main`. You can run it from your IDE or by building a runnable jar.

To build a runnable jar:
```bash
mvn package
```

Then run the jar:
```bash
java -jar target/match-main-1.0-SNAPSHOT.jar
```

## Running the tests

To run the automated tests for this system, use:

```bash
mvn test
```

## Built With

*   [Maven](https://maven.apache.org/) - Dependency Management 