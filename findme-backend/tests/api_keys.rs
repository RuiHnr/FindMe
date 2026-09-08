mod helpers;
use axum::http::StatusCode;
use findme_backend::models::{
    OneTimePreKeyDto, PreKeyBundleResponse, SignedPreKeyDto, UploadKeysRequest, KeyCountResponse
};

#[sqlx::test]
async fn test_upload_and_fetch_prekey_bundle(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;

    // 1. Create two users: Alice and Bob
    let alice = app.create_user("alice", "alice_identity_key_123").await;
    let bob = app.create_user("bob", "bob_identity_key_456").await;

    // 2. Establish friendship between Alice and Bob
    let req_res = app.send_friend_request(&alice.token, "bob").await;
    assert_eq!(req_res.status_code(), StatusCode::OK);

    let accept_res = app.accept_friend_request(&bob.token, alice.user_id).await;
    assert_eq!(accept_res.status_code(), StatusCode::OK);

    // 3. Bob uploads his Signed PreKey and 2 One-Time PreKeys
    let upload_payload = UploadKeysRequest {
        signed_prekey: Some(SignedPreKeyDto {
            key_id: 1,
            public_key: "bob_spk_key_1".to_string(),
            signature: "bob_spk_sig_1".to_string(),
        }),
        one_time_prekeys: Some(vec![
            OneTimePreKeyDto {
                key_id: 101,
                public_key: "bob_otpk_101".to_string(),
            },
            OneTimePreKeyDto {
                key_id: 102,
                public_key: "bob_otpk_102".to_string(),
            },
        ]),
    };

    let upload_res = app
        .server
        .post("/keys")
        .add_header("Authorization", format!("Bearer {}", bob.token))
        .json(&upload_payload)
        .await;

    assert_eq!(upload_res.status_code(), StatusCode::OK);

    // 4. Bob checks remaining one-time prekeys count
    let count_res = app
        .server
        .get("/keys/count")
        .add_header("Authorization", format!("Bearer {}", bob.token))
        .await;
    assert_eq!(count_res.status_code(), StatusCode::OK);
    let count_body: KeyCountResponse = count_res.json();
    assert_eq!(count_body.remaining_one_time_prekeys, 2);

    // 5. Alice fetches Bob's PreKey bundle to initiate a session
    let bundle_res = app
        .server
        .get(&format!("/keys/{}", bob.user_id))
        .add_header("Authorization", format!("Bearer {}", alice.token))
        .await;
    assert_eq!(bundle_res.status_code(), StatusCode::OK);

    let bundle: PreKeyBundleResponse = bundle_res.json();
    assert_eq!(bundle.user_id, bob.user_id);
    assert_eq!(bundle.identity_key, "bob_identity_key_456");
    assert_eq!(bundle.signed_prekey.key_id, 1);
    assert!(bundle.one_time_prekey.is_some());
    let first_otpk = bundle.one_time_prekey.unwrap();
    assert_eq!(first_otpk.key_id, 101);

    // 6. Bob's key count should now be 1 because the consumed one-time key was deleted
    let count_res2 = app
        .server
        .get("/keys/count")
        .add_header("Authorization", format!("Bearer {}", bob.token))
        .await;
    let count_body2: KeyCountResponse = count_res2.json();
    assert_eq!(count_body2.remaining_one_time_prekeys, 1);

    // 7. Alice fetches a second bundle -> gets key 102
    let bundle_res2 = app
        .server
        .get(&format!("/keys/{}", bob.user_id))
        .add_header("Authorization", format!("Bearer {}", alice.token))
        .await;
    let bundle2: PreKeyBundleResponse = bundle_res2.json();
    assert_eq!(bundle2.one_time_prekey.unwrap().key_id, 102);

    // 8. Alice fetches a third bundle -> one-time keys are depleted, but bundle still returns with None
    let bundle_res3 = app
        .server
        .get(&format!("/keys/{}", bob.user_id))
        .add_header("Authorization", format!("Bearer {}", alice.token))
        .await;
    let bundle3: PreKeyBundleResponse = bundle_res3.json();
    assert!(bundle3.one_time_prekey.is_none());
}

#[sqlx::test]
async fn test_prekey_bundle_unauthorized_non_friend(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;

    let alice = app.create_user("alice", "alice_key").await;
    let eve = app.create_user("eve", "eve_key").await;

    // Eve is NOT friends with Alice, should not be able to fetch Alice's prekey bundle
    let res = app
        .server
        .get(&format!("/keys/{}", alice.user_id))
        .add_header("Authorization", format!("Bearer {}", eve.token))
        .await;

    assert_eq!(res.status_code(), StatusCode::FORBIDDEN);
}
