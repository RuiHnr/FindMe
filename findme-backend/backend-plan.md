# Plan: Backend Completion
- **Security Loophole - DoS**: an attacker who knows Bob's UUID can call the endpoint `/inbox/{receiver_id}` to delete all of Bob's incoming messages

## Refactoring
- split current *main.rs* into *handlers.rs*, *models.rs* and *db.rs*

## User-Management & Public Keys
- create DB table `users`
- build a POST-route `/users/register`
- Server should save user's static *Public Key* (for initial Handshake)

## Authentification
- **Close Security Loophole:** when registering, a user gets a token (e.g. JWT - JSON Web Token / simple Session-Token)
- implement a **Middleware** in Axum to validate token in every request; No Token -> `401 Unauthorized`

## Friendship System
- create DB Table `friendships`
- build endpoints to send friend requests (`POST /friends/request`) and to accept (`PUT /friends/accept`)
- only friends can send packages to each other

## Production & Deployment Prep
- configure Dockerfile for the backend
- configure Logging