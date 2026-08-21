package com.bankflow;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.*;
import java.time.*;
import java.util.*;

enum TransactionType { DEPOSIT, WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT }
enum TransactionStatus { COMPLETED, DECLINED }

@Entity @Table(name="customers") class Customer {
 @Id @GeneratedValue(strategy=GenerationType.UUID) String id; @Column(nullable=false) String fullName; @Column(nullable=false,unique=true) String email; Instant createdAt=Instant.now();
 protected Customer(){} Customer(String name,String email){this.fullName=name;this.email=email.toLowerCase();}
}
@Entity @Table(name="accounts") class Account {
 @Id @GeneratedValue(strategy=GenerationType.UUID) String id; @Column(nullable=false,unique=true) String accountNumber; @Column(nullable=false) String customerId; @Column(nullable=false,precision=19,scale=2) BigDecimal balance=BigDecimal.ZERO; @Version Long version; Instant createdAt=Instant.now();
 protected Account(){} Account(String customerId){this.customerId=customerId;this.accountNumber="BF"+String.format("%010d",Math.abs(UUID.randomUUID().getMostSignificantBits()%10_000_000_000L));}
}
@Entity @Table(name="transactions", uniqueConstraints=@UniqueConstraint(columnNames="idempotencyKey")) class BankTransaction {
 @Id @GeneratedValue(strategy=GenerationType.UUID) String id; @Column(nullable=false) String accountId; String counterpartyAccountId; @Enumerated(EnumType.STRING) TransactionType type; @Enumerated(EnumType.STRING) TransactionStatus status; @Column(nullable=false,precision=19,scale=2) BigDecimal amount; String description; String idempotencyKey; Instant createdAt=Instant.now();
 protected BankTransaction(){} BankTransaction(String accountId,String other,TransactionType type,BigDecimal amount,String description,String key){this.accountId=accountId;this.counterpartyAccountId=other;this.type=type;this.amount=amount;this.description=description;this.idempotencyKey=key;this.status=TransactionStatus.COMPLETED;}
}

interface CustomerRepo extends org.springframework.data.jpa.repository.JpaRepository<Customer,String>{ boolean existsByEmail(String email); }
interface AccountRepo extends org.springframework.data.jpa.repository.JpaRepository<Account,String>{ List<Account> findByCustomerId(String customerId); @org.springframework.data.jpa.repository.Lock(LockModeType.PESSIMISTIC_WRITE) @org.springframework.data.jpa.repository.Query("select a from Account a where a.id=:id") Optional<Account> lockById(@org.springframework.data.repository.query.Param("id") String id); }
interface TransactionRepo extends org.springframework.data.jpa.repository.JpaRepository<BankTransaction,String>{ Optional<BankTransaction> findByIdempotencyKey(String key); List<BankTransaction> findByAccountIdOrderByCreatedAtDesc(String accountId); }

record CreateCustomer(@NotBlank @Size(max=100) String fullName,@NotBlank @Email String email){}
record CreateAccount(@NotBlank String customerId){}
record MoneyRequest(@NotNull @DecimalMin(value="0.01") @Digits(integer=17,fraction=2) BigDecimal amount,@Size(max=160) String description){}
record TransferRequest(@NotBlank String fromAccountId,@NotBlank String toAccountId,@NotNull @DecimalMin(value="0.01") @Digits(integer=17,fraction=2) BigDecimal amount,@Size(max=160) String description){}
record CustomerResponse(String id,String fullName,String email,Instant createdAt){}
record AccountResponse(String id,String accountNumber,String customerId,BigDecimal balance,Instant createdAt){}
record TransactionResponse(String id,String accountId,String counterpartyAccountId,TransactionType type,TransactionStatus status,BigDecimal amount,String description,Instant createdAt){}

@RestController @RequestMapping("/api/v1") class BankingController {
 private final BankService service; BankingController(BankService service){this.service=service;}
 @PostMapping("/customers") @ResponseStatus(HttpStatus.CREATED) CustomerResponse customer(@Valid @RequestBody CreateCustomer r){return service.createCustomer(r);}
 @PostMapping("/accounts") @ResponseStatus(HttpStatus.CREATED) AccountResponse account(@Valid @RequestBody CreateAccount r){return service.createAccount(r);}
 @GetMapping("/accounts/{id}") AccountResponse account(@PathVariable String id){return service.account(id);}
 @GetMapping("/customers/{id}/accounts") List<AccountResponse> accounts(@PathVariable String id){return service.accounts(id);}
 @PostMapping("/accounts/{id}/deposits") TransactionResponse deposit(@PathVariable String id,@Valid @RequestBody MoneyRequest r,@RequestHeader("Idempotency-Key") String key){return service.deposit(id,r,key);}
 @PostMapping("/accounts/{id}/withdrawals") TransactionResponse withdraw(@PathVariable String id,@Valid @RequestBody MoneyRequest r,@RequestHeader("Idempotency-Key") String key){return service.withdraw(id,r,key);}
 @PostMapping("/transfers") TransactionResponse transfer(@Valid @RequestBody TransferRequest r,@RequestHeader("Idempotency-Key") String key){return service.transfer(r,key);}
 @GetMapping("/accounts/{id}/transactions") List<TransactionResponse> history(@PathVariable String id){return service.history(id);}
 @ExceptionHandler(NoSuchElementException.class) ResponseEntity<Map<String,String>> missing(NoSuchElementException e){return ResponseEntity.status(404).body(Map.of("error",e.getMessage()));}
 @ExceptionHandler({IllegalArgumentException.class,DataIntegrityViolationException.class}) ResponseEntity<Map<String,String>> bad(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e instanceof DataIntegrityViolationException?"Duplicate email or idempotency key.":e.getMessage()));}
}

@Service class BankService {
 private final CustomerRepo customers; private final AccountRepo accounts; private final TransactionRepo transactions;
 BankService(CustomerRepo c,AccountRepo a,TransactionRepo t){customers=c;accounts=a;transactions=t;}
 @Transactional CustomerResponse createCustomer(CreateCustomer r){if(customers.existsByEmail(r.email().toLowerCase()))throw new IllegalArgumentException("An account already exists for this email.");return customer(customers.save(new Customer(r.fullName().trim(),r.email().trim())));}
 @Transactional AccountResponse createAccount(CreateAccount r){customerRequired(r.customerId());return account(accounts.save(new Account(r.customerId())));}
 @Transactional(readOnly=true) AccountResponse account(String id){return account(accountRequired(id));}
 @Transactional(readOnly=true) List<AccountResponse> accounts(String customerId){customerRequired(customerId);return accounts.findByCustomerId(customerId).stream().map(this::account).toList();}
 @Transactional TransactionResponse deposit(String accountId,MoneyRequest r,String key){BankTransaction old=previous(key);if(old!=null)return tx(old);Account a=locked(accountId);a.balance=a.balance.add(r.amount());return tx(transactions.save(new BankTransaction(a.id,null,TransactionType.DEPOSIT,r.amount(),safe(r.description(),"Cash deposit"),key)));}
 @Transactional TransactionResponse withdraw(String accountId,MoneyRequest r,String key){BankTransaction old=previous(key);if(old!=null)return tx(old);Account a=locked(accountId);ensureFunds(a,r.amount());a.balance=a.balance.subtract(r.amount());return tx(transactions.save(new BankTransaction(a.id,null,TransactionType.WITHDRAWAL,r.amount(),safe(r.description(),"Cash withdrawal"),key)));}
 @Transactional TransactionResponse transfer(TransferRequest r,String key){BankTransaction old=previous(key);if(old!=null)return tx(old);if(r.fromAccountId().equals(r.toAccountId()))throw new IllegalArgumentException("Source and destination must differ.");
  // Lock in stable order to prevent concurrent transfers from deadlocking.
  String first=r.fromAccountId().compareTo(r.toAccountId())<0?r.fromAccountId():r.toAccountId(), second=first.equals(r.fromAccountId())?r.toAccountId():r.fromAccountId(); Account one=locked(first),two=locked(second); Account from=one.id.equals(r.fromAccountId())?one:two,to=from==one?two:one;
  ensureFunds(from,r.amount());from.balance=from.balance.subtract(r.amount());to.balance=to.balance.add(r.amount());String description=safe(r.description(),"Account transfer");transactions.save(new BankTransaction(to.id,from.id,TransactionType.TRANSFER_IN,r.amount(),description,null));return tx(transactions.save(new BankTransaction(from.id,to.id,TransactionType.TRANSFER_OUT,r.amount(),description,key)));}
 @Transactional(readOnly=true) List<TransactionResponse> history(String accountId){accountRequired(accountId);return transactions.findByAccountIdOrderByCreatedAtDesc(accountId).stream().map(this::tx).toList();}
 private BankTransaction previous(String key){if(key==null||key.isBlank())throw new IllegalArgumentException("Idempotency-Key header is required.");return transactions.findByIdempotencyKey(key).orElse(null);}
 private Account locked(String id){return accounts.lockById(id).orElseThrow(()->new NoSuchElementException("Account not found."));} private Account accountRequired(String id){return accounts.findById(id).orElseThrow(()->new NoSuchElementException("Account not found."));} private void customerRequired(String id){if(!customers.existsById(id))throw new NoSuchElementException("Customer not found.");} private void ensureFunds(Account a,BigDecimal amount){if(a.balance.compareTo(amount)<0)throw new IllegalArgumentException("Insufficient funds.");} private String safe(String v,String fallback){return v==null||v.isBlank()?fallback:v.trim();}
 private CustomerResponse customer(Customer c){return new CustomerResponse(c.id,c.fullName,c.email,c.createdAt);} private AccountResponse account(Account a){return new AccountResponse(a.id,a.accountNumber,a.customerId,a.balance,a.createdAt);} private TransactionResponse tx(BankTransaction t){return new TransactionResponse(t.id,t.accountId,t.counterpartyAccountId,t.type,t.status,t.amount,t.description,t.createdAt);}
}
