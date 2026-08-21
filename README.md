# BankFlow — Online Banking REST API

A resume-ready banking-domain backend built with **Java 17, Spring Boot, Spring Data JPA, Hibernate, and H2**. It simulates core banking operations locally; it does **not** connect to real bank accounts, card networks, or payment rails.

## Highlights

- Customer and account creation with unique email validation.
- Decimal-safe monetary operations using `BigDecimal`.
- Deposits, withdrawals, internal transfers, and account transaction history.
- Atomic transfers with pessimistic account locks and a stable lock order to reduce concurrency risks.
- Idempotency keys on all money-moving endpoints, preventing a client retry from posting the same movement twice.
- Request validation, consistent errors, and an H2 database console for local inspection.

## Run it

Prerequisites: Java 17+ and Maven 3.9+.

```bash
mvn spring-boot:run
```

The API starts at `http://localhost:8080`; the H2 console is at `http://localhost:8080/h2-console` with JDBC URL `jdbc:h2:mem:bankflow`.

## Quick start

Create a customer:

```bash
curl -X POST http://localhost:8080/api/v1/customers -H "Content-Type: application/json" -d '{"fullName":"Asha Sharma","email":"asha@example.com"}'
```

Create an account using the returned customer ID:

```bash
curl -X POST http://localhost:8080/api/v1/accounts -H "Content-Type: application/json" -d '{"customerId":"CUSTOMER_ID"}'
```

Deposit money using the returned account ID:

```bash
curl -X POST http://localhost:8080/api/v1/accounts/ACCOUNT_ID/deposits -H "Content-Type: application/json" -H "Idempotency-Key: deposit-001" -d '{"amount":1000.00,"description":"Initial funding"}'
```

## Endpoint summary

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/v1/customers` | Create customer |
| POST | `/api/v1/accounts` | Open account |
| GET | `/api/v1/accounts/{id}` | Account/balance |
| GET | `/api/v1/customers/{id}/accounts` | Customer accounts |
| POST | `/api/v1/accounts/{id}/deposits` | Deposit money |
| POST | `/api/v1/accounts/{id}/withdrawals` | Withdraw money |
| POST | `/api/v1/transfers` | Internal transfer |
| GET | `/api/v1/accounts/{id}/transactions` | Transaction history |

All money-moving endpoints require a unique `Idempotency-Key` HTTP header. Repeating a request with the same key returns the original transaction.

## Resume entry

**BankFlow — Online Banking REST API** | Java, Spring Boot, Spring Data JPA, Hibernate, H2, REST APIs

- Developed a banking-domain REST API for customer onboarding, account management, deposits, withdrawals, transfers, and transaction history.
- Implemented `BigDecimal`-based monetary calculations, idempotency keys, and transactional pessimistic locks to protect balance consistency during concurrent money movements.
- Designed validated REST endpoints with domain-specific error handling and an H2-backed persistence layer for fast local development and testing.

## Production note

This is an educational portfolio project. A production financial system also needs authentication/authorization, encryption and key management, audit controls, fraud detection, rate limiting, regulatory compliance, observability, and an externally managed database.
