package zw.co.innbucks.loans.web;

/**
 * Example bodies for GetMore's endpoints, as one story, kept apart from the voucher 7 and 8 stories: Rutendo Chari
 * (E1064, staff member 9, register phone 263771640064) accepts SGL-2026-000164 (loan 164) for USD 150.00 in the
 * SuperApp at 10:20:05 on Wednesday 14 October, due on 20 November. GetMore's simulated store (staging) makes coupon
 * 1207 under rule 43 on the issuance's prefix {@code SIM-K7Q2M3XW-}, kept as voucher 9 and the loan DISBURSED as
 * {@code GETMORE-SIM-1207} at 10:20:07; it lapses at the end of 13 November. She spends USD 45.50 (order SIM000000012)
 * and USD 54.50 (order SIM000000013, held at the till until it was invoiced) at GetMore Avondale, and USD 10.00 of the
 * first comes back by credit memo SIMCM00000003: USD 60.00 left to spend. Her code is masked {@code ****8B1C}
 * everywhere but where she sees it in full.
 */
public final class GetMoreApiExamples {

    private GetMoreApiExamples() {
    }

    /** Voucher 9's code, as GetMore's simulated store made it: what the borrower sees and the till takes. */
    public static final String CODE_9 = "SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C";

    static final String ASSUMPTIONS = """
            [
                  "A1 per-coupon allowance",
                  "A2 spending in parts",
                  "A3 refund restores balance",
                  "A4 coupon expiry enforced"
                ]""";

    // ---- voucher 9 as staff see it, at each step ------------------------------------------------------------------

    private static final String VOUCHER_9_HEAD = """
                {
                  "id": 9,
                  "product": "STAFF_GROCERY_LOAN",
                  "merchantCode": "getmore-groceries",
                  "merchantName": "GetMore Groceries",
                  "loanAccount": "SGL-2026-000164",
                  "disbursementReference": "GETMORE-SIM-1207",
                  "customerReference": "E1064",
                  "customerName": "Rutendo Chari",
                  "customerMsisdn": "****0064",
                  "maskedCode": "****8B1C",
                  "faceValue": 150.00,""";

    private static final String VOUCHER_9_DELIVERY = """
                  "cancelledBy": null,
                  "cancelledAt": null,
                  "cancellationReason": null,
                  "deliveryStatus": "SENT",
                  "deliveredChannel": "WHATSAPP",
                  "deliveryUpdatedAt": "2026-10-14T10:20:08+02:00",
                  "codeSource": "GETMORE",
                  "storeMode": "SIMULATED",
                  "externalCouponId": 1207,""";

    /** After the first sale: USD 45.50 spent, USD 104.50 left. */
    static final String VOUCHER_9_AFTER_SALE = VOUCHER_9_HEAD + """

                  "redeemedAmount": 45.50,
                  "balance": 104.50,
                  "currency": "USD",
                  "issuedAt": "2026-10-14T10:20:07+02:00",
                  "expiresAt": "2026-11-13T23:59:59+02:00",
                  "status": "PARTIALLY_REDEEMED",
                  "lastRedeemedAt": "2026-10-14T10:31:02+02:00",
                  "lastRedeemedOutlet": "GetMore Avondale",
            """ + VOUCHER_9_DELIVERY + """

                  "refundedAmount": 0.00,
                  "heldAmount": 0.00,
                  "syncedAt": "2026-10-14T10:31:02+02:00"
                }""";

    /** After the second sale was invoiced: USD 100.00 spent, USD 50.00 left. */
    private static final String VOUCHER_9_AFTER_INVOICE = VOUCHER_9_HEAD + """

                  "redeemedAmount": 100.00,
                  "balance": 50.00,
                  "currency": "USD",
                  "issuedAt": "2026-10-14T10:20:07+02:00",
                  "expiresAt": "2026-11-13T23:59:59+02:00",
                  "status": "PARTIALLY_REDEEMED",
                  "lastRedeemedAt": "2026-10-14T10:35:40+02:00",
                  "lastRedeemedOutlet": "GetMore Avondale",
            """ + VOUCHER_9_DELIVERY + """

                  "refundedAmount": 0.00,
                  "heldAmount": 0.00,
                  "syncedAt": "2026-10-14T10:37:15+02:00"
                }""";

    /** After the refund: USD 90.00 spent net of USD 10.00 refunded, USD 60.00 left. */
    static final String VOUCHER_9_AFTER_REFUND = VOUCHER_9_HEAD + """

                  "redeemedAmount": 90.00,
                  "balance": 60.00,
                  "currency": "USD",
                  "issuedAt": "2026-10-14T10:20:07+02:00",
                  "expiresAt": "2026-11-13T23:59:59+02:00",
                  "status": "PARTIALLY_REDEEMED",
                  "lastRedeemedAt": "2026-10-14T10:35:40+02:00",
                  "lastRedeemedOutlet": "GetMore Avondale",
            """ + VOUCHER_9_DELIVERY + """

                  "refundedAmount": 10.00,
                  "heldAmount": 0.00,
                  "syncedAt": "2026-10-14T10:39:58+02:00"
                }""";

    /** Expired now by the simulator, with USD 60.00 unspent; its code withdrawn at the store. */
    private static final String VOUCHER_9_EXPIRED = VOUCHER_9_HEAD + """

                  "redeemedAmount": 90.00,
                  "balance": 60.00,
                  "currency": "USD",
                  "issuedAt": "2026-10-14T10:20:07+02:00",
                  "expiresAt": "2026-10-14T10:45:12+02:00",
                  "status": "EXPIRED",
                  "lastRedeemedAt": "2026-10-14T10:35:40+02:00",
                  "lastRedeemedOutlet": "GetMore Avondale",
            """ + VOUCHER_9_DELIVERY + """

                  "refundedAmount": 10.00,
                  "heldAmount": 0.00,
                  "stoppedReason": "SIMULATOR_EXPIRED",
                  "syncedAt": "2026-10-14T10:45:12+02:00",
                  "couponWithdrawnAt": "2026-10-14T10:45:12+02:00"
                }""";

    // ---- the store's own shapes (Magento's, codes masked) -----------------------------------------------------------

    private static final String ORDER_12 = """
                  {
                    "entity_id": 12,
                    "increment_id": "SIM000000012",
                    "coupon_code": "****8B1C",
                    "applied_rule_ids": "43",
                    "state": "complete",
                    "status": "complete",
                    "order_currency_code": "USD",
                    "base_currency_code": "USD",
                    "subtotal": 60.0000,
                    "grand_total": 14.5000,
                    "discount_amount": -45.5000,
                    "discount_invoiced": -45.5000,
                    "store_id": 2,
                    "store_name": "GetMore\\nGetMore Avondale\\nDefault Store View",
                    "created_at": "2026-10-14 08:31:02",
                    "updated_at": "2026-10-14 08:31:02"
                  }""";

    private static final String ORDER_12_REFUNDED = """
                  {
                    "entity_id": 12,
                    "increment_id": "SIM000000012",
                    "coupon_code": "****8B1C",
                    "applied_rule_ids": "43",
                    "state": "complete",
                    "status": "complete",
                    "order_currency_code": "USD",
                    "base_currency_code": "USD",
                    "subtotal": 60.0000,
                    "grand_total": 14.5000,
                    "discount_amount": -45.5000,
                    "discount_invoiced": -45.5000,
                    "discount_refunded": -10.0000,
                    "store_id": 2,
                    "store_name": "GetMore\\nGetMore Avondale\\nDefault Store View",
                    "created_at": "2026-10-14 08:31:02",
                    "updated_at": "2026-10-14 08:39:58"
                  }""";

    private static final String ORDER_13_HELD = """
                  {
                    "entity_id": 13,
                    "increment_id": "SIM000000013",
                    "coupon_code": "****8B1C",
                    "applied_rule_ids": "43",
                    "state": "processing",
                    "status": "processing",
                    "order_currency_code": "USD",
                    "base_currency_code": "USD",
                    "subtotal": 54.5000,
                    "grand_total": 0.0000,
                    "discount_amount": -54.5000,
                    "store_id": 2,
                    "store_name": "GetMore\\nGetMore Avondale\\nDefault Store View",
                    "created_at": "2026-10-14 08:35:40",
                    "updated_at": "2026-10-14 08:35:40"
                  }""";

    private static final String ORDER_13 = """
                  {
                    "entity_id": 13,
                    "increment_id": "SIM000000013",
                    "coupon_code": "****8B1C",
                    "applied_rule_ids": "43",
                    "state": "complete",
                    "status": "complete",
                    "order_currency_code": "USD",
                    "base_currency_code": "USD",
                    "subtotal": 54.5000,
                    "grand_total": 0.0000,
                    "discount_amount": -54.5000,
                    "discount_invoiced": -54.5000,
                    "store_id": 2,
                    "store_name": "GetMore\\nGetMore Avondale\\nDefault Store View",
                    "created_at": "2026-10-14 08:35:40",
                    "updated_at": "2026-10-14 08:37:15"
                  }""";

    private static final String CREDIT_MEMO_3 = """
                  {
                    "entity_id": 3,
                    "increment_id": "SIMCM00000003",
                    "order_id": 12,
                    "state": 2,
                    "discount_amount": -10.0000,
                    "subtotal": 10.0000,
                    "grand_total": 0.0000,
                    "created_at": "2026-10-14 08:39:58"
                  }""";

    private static final String COUPON_1207 = """
                  {
                    "coupon_id": 1207,
                    "rule_id": 43,
                    "code": "****8B1C",
                    "usage_limit": 0,
                    "usage_per_customer": 0,
                    "times_used": 2,
                    "expiration_date": "2026-11-13 21:59:59",
                    "is_primary": null,
                    "created_at": "2026-10-14 08:20:06",
                    "type": 1
                  }""";

    private static final String COUPON_1207_EXPIRED = """
                  {
                    "coupon_id": 1207,
                    "rule_id": 43,
                    "code": "****8B1C",
                    "usage_limit": 0,
                    "usage_per_customer": 0,
                    "times_used": 2,
                    "expiration_date": "2026-10-14 08:45:12",
                    "is_primary": null,
                    "created_at": "2026-10-14 08:20:06",
                    "type": 1
                  }""";

    private static final String RULE_43 = """
                  {
                    "rule_id": 43,
                    "name": "InnBucks Staff Grocery (SIMULATED)",
                    "uses_per_customer": 0,
                    "is_active": true,
                    "stop_rules_processing": true,
                    "simple_action": "cart_fixed",
                    "discount_amount": 0.0000,
                    "apply_to_shipping": false,
                    "times_used": 2,
                    "coupon_type": "AUTO",
                    "use_auto_generation": true,
                    "uses_per_coupon": 0
                  }""";

    // ---- the staging test actions ------------------------------------------------------------------------------------

    public static final String SALE_REQUEST = """
            {
              "loanReference": "SGL-2026-000164",
              "amount": 45.50,
              "currency": "USD",
              "basketTotal": 60.00,
              "outletId": "GM-AVD",
              "outletName": "GetMore Avondale",
              "reference": "AVD-T3-000981",
              "invoiced": true
            }""";

    public static final String SALE = """
            {
              "code": "CREATED",
              "message": "Sale SIM000000012: USD 45.50 off the voucher; USD 104.50 left",
              "data": {
                "store": {
                  "order":""" + ORDER_12 + """
            ,
                  "balance": 104.5000,
                  "replayed": false
                },
                "voucher":""" + VOUCHER_9_AFTER_SALE + """
            ,
                "sync": {
                  "outcome": "SYNCED",
                  "spendsRecorded": 1,
                  "refundsRecorded": 0,
                  "heldAmount": 0.00,
                  "issues": []
                },
                "assumptions": """ + ASSUMPTIONS + """

              }
            }""";

    public static final String SALE_REPLAYED = """
            {
              "code": "OK",
              "message": "Sale SIM000000012 was already made under reference AVD-T3-000981; nothing more was spent",
              "data": {
                "store": {
                  "order":""" + ORDER_12 + """
            ,
                  "balance": 104.5000,
                  "replayed": true
                },
                "voucher":""" + VOUCHER_9_AFTER_SALE + """
            ,
                "sync": {
                  "outcome": "UNCHANGED",
                  "spendsRecorded": 0,
                  "refundsRecorded": 0,
                  "heldAmount": 0.00,
                  "issues": []
                },
                "assumptions": """ + ASSUMPTIONS + """

              }
            }""";

    public static final String SALE_HELD_REQUEST = """
            {
              "loanReference": "SGL-2026-000164",
              "amount": 54.50,
              "outletId": "GM-AVD",
              "outletName": "GetMore Avondale",
              "reference": "AVD-T3-000994",
              "invoiced": false
            }""";

    public static final String SALE_HELD = """
            {
              "code": "CREATED",
              "message": "Sale SIM000000013: USD 54.50 held on the voucher until it is invoiced; USD 50.00 left",
              "data": {
                "store": {
                  "order":""" + ORDER_13_HELD + """
            ,
                  "balance": 50.0000,
                  "replayed": false
                },
                "voucher":""" + VOUCHER_9_HEAD + """

                  "redeemedAmount": 45.50,
                  "balance": 104.50,
                  "currency": "USD",
                  "issuedAt": "2026-10-14T10:20:07+02:00",
                  "expiresAt": "2026-11-13T23:59:59+02:00",
                  "status": "PARTIALLY_REDEEMED",
                  "lastRedeemedAt": "2026-10-14T10:31:02+02:00",
                  "lastRedeemedOutlet": "GetMore Avondale",
            """ + VOUCHER_9_DELIVERY + """

                  "refundedAmount": 0.00,
                  "heldAmount": 54.50,
                  "syncedAt": "2026-10-14T10:35:40+02:00"
                },
                "sync": {
                  "outcome": "SYNCED",
                  "spendsRecorded": 0,
                  "refundsRecorded": 0,
                  "heldAmount": 54.50,
                  "issues": []
                },
                "assumptions": """ + ASSUMPTIONS + """

              }
            }""";

    public static final String INVOICED = """
            {
              "code": "OK",
              "message": "Sale SIM000000013 invoiced: USD 54.50 off the voucher",
              "data": {
                "store": {
                  "order":""" + ORDER_13 + """
            ,
                  "balance": 50.0000,
                  "replayed": false
                },
                "voucher":""" + VOUCHER_9_AFTER_INVOICE + """
            ,
                "sync": {
                  "outcome": "SYNCED",
                  "spendsRecorded": 1,
                  "refundsRecorded": 0,
                  "heldAmount": 0.00,
                  "issues": []
                },
                "assumptions": """ + ASSUMPTIONS + """

              }
            }""";

    public static final String REFUND_REQUEST = """
            {
              "orderNumber": "SIM000000012",
              "amount": 10.00,
              "reference": "AVD-RF-0012"
            }""";

    public static final String REFUNDED = """
            {
              "code": "CREATED",
              "message": "Credit memo SIMCM00000003: USD 10.00 back on the voucher; USD 60.00 left",
              "data": {
                "store": {
                  "creditMemo":""" + CREDIT_MEMO_3 + """
            ,
                  "balance": 60.0000,
                  "replayed": false
                },
                "voucher":""" + VOUCHER_9_AFTER_REFUND + """
            ,
                "sync": {
                  "outcome": "SYNCED",
                  "spendsRecorded": 0,
                  "refundsRecorded": 1,
                  "heldAmount": 0.00,
                  "issues": []
                },
                "assumptions": """ + ASSUMPTIONS + """

              }
            }""";

    public static final String EXPIRY_REQUEST = """
            {
              "loanReference": "SGL-2026-000164",
              "reason": "test lapse"
            }""";

    public static final String EXPIRED = """
            {
              "code": "OK",
              "message": "Voucher 9 expired now (simulation) with USD 60.00 unspent",
              "data": {
                "store": {
                  "coupon":""" + COUPON_1207_EXPIRED + """

                },
                "voucher":""" + VOUCHER_9_EXPIRED + """
            ,
                "sync": {
                  "outcome": "SYNCED",
                  "spendsRecorded": 0,
                  "refundsRecorded": 0,
                  "heldAmount": 0.00,
                  "issues": []
                },
                "couponWithdrawn": true,
                "assumptions": """ + ASSUMPTIONS + """

              }
            }""";

    public static final String LOOKUP_REQUEST = """
            {
              "loanReference": "SGL-2026-000164"
            }""";

    public static final String LOOKUP = """
            {
              "code": "OK",
              "message": "The store's view of coupon ****8B1C",
              "data": {
                "store": {
                  "coupon":""" + COUPON_1207 + """
            ,
                  "rule":""" + RULE_43 + """
            ,
                  "orders": {
                    "items": [""" + ORDER_12_REFUNDED + "," + ORDER_13 + """

                    ],
                    "total_count": 2
                  },
                  "creditmemos": {
                    "items": [""" + CREDIT_MEMO_3 + """

                    ],
                    "total_count": 1
                  }
                },
                "allowance": 150.0000,
                "allowanceCurrency": "USD",
                "balance": 60.0000,
                "withdrawn": false,
                "assumptions": """ + ASSUMPTIONS + """

              }
            }""";

    public static final String FAULT_REQUEST = """
            {
              "operation": "GENERATE",
              "kind": "LOSE_RESPONSE",
              "times": 1
            }""";

    public static final String FAULT_QUEUED = """
            {
              "code": "CREATED",
              "message": "The next 1 GENERATE call(s) will LOSE_RESPONSE",
              "data": {
                "pending": {
                  "GENERATE": ["LOSE_RESPONSE"]
                },
                "assumptions": """ + ASSUMPTIONS + """

              }
            }""";

    public static final String FAULTS_CLEARED = """
            {
              "code": "OK",
              "message": "Faults cleared",
              "data": {
                "pending": {},
                "assumptions": """ + ASSUMPTIONS + """

              }
            }""";

    public static final String SIMULATOR_KEY_REFUSED = """
            {
              "code": "UNAUTHORIZED",
              "message": "Invalid or missing api key"
            }""";

    public static final String SIMULATOR_TOO_LARGE = """
            {
              "code": "PAYLOAD_TOO_LARGE",
              "message": "The request body must be at most 8192 bytes"
            }""";

    public static final String SALE_INVALID = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "amount": "amount must have at most 2 decimal places and 8 digits before them",
                "loanReferenceOrCode": "give exactly one of loanReference and code"
              }
            }""";

    public static final String NO_GETMORE_VOUCHER = """
            {
              "code": "NOT_FOUND",
              "message": "No GetMore voucher for loan SGL-2026-000164"
            }""";

    /** Expiring by a code no GetMore voucher was kept for: a code of loans' own, or none at all. */
    public static final String NO_GETMORE_VOUCHER_FOR_CODE = """
            {
              "code": "NOT_FOUND",
              "message": "No GetMore voucher has that code"
            }""";

    public static final String SIM_COUPON_NOT_FOUND = """
            {
              "code": "SIM_COUPON_NOT_FOUND",
              "message": "The coupon code \\"****8B1C\\" is not valid."
            }""";

    public static final String SIM_COUPON_EXPIRED = """
            {
              "code": "SIM_COUPON_EXPIRED",
              "message": "The coupon code \\"****8B1C\\" has expired."
            }""";

    public static final String SIM_INSUFFICIENT_BALANCE = """
            {
              "code": "SIM_INSUFFICIENT_BALANCE",
              "message": "The amount is more than the USD 50.00 left on this coupon"
            }""";

    public static final String SIM_PARTIAL_SPEND_NOT_ALLOWED = """
            {
              "code": "SIM_PARTIAL_SPEND_NOT_ALLOWED",
              "message": "This coupon must be spent in one purchase of USD 150.00"
            }""";

    public static final String SIM_CURRENCY_MISMATCH = """
            {
              "code": "SIM_CURRENCY_MISMATCH",
              "message": "The simulated store sells in USD, not ZWG"
            }""";

    public static final String SIM_SALE_REFERENCE_REUSED = """
            {
              "code": "SIM_REFERENCE_REUSED",
              "message": "Sale reference AVD-T3-000981 was already used for a different sale (SIM000000012)"
            }""";

    public static final String SIM_SALE_NOT_FOUND = """
            {
              "code": "SIM_SALE_NOT_FOUND",
              "message": "No simulated sale has order number SIM000000099"
            }""";

    public static final String SIM_NOTHING_TO_INVOICE = """
            {
              "code": "SIM_NOTHING_TO_INVOICE",
              "message": "Sale SIM000000012 has nothing left to invoice"
            }""";

    public static final String SIM_NOTHING_TO_REFUND = """
            {
              "code": "SIM_NOTHING_TO_REFUND",
              "message": "Sale SIM000000013 has nothing invoiced left to refund"
            }""";

    public static final String SIM_REFUND_TOO_LARGE = """
            {
              "code": "SIM_REFUND_TOO_LARGE",
              "message": "The amount is more than the USD 35.50 left to refund on sale SIM000000012"
            }""";

    public static final String SIM_REFUND_REFERENCE_REUSED = """
            {
              "code": "SIM_REFERENCE_REUSED",
              "message": "Refund reference AVD-RF-0012 was already used for a different refund (SIMCM00000003)"
            }""";

    /** Only a LIVE GetMore voucher: a voucher of loans' own is not a GetMore one at all (404 NOT_FOUND). */
    public static final String NOT_A_SIMULATED_VOUCHER = """
            {
              "code": "NOT_A_SIMULATED_VOUCHER",
              "message": "Voucher 31 is not a simulated GetMore voucher"
            }""";

    public static final String ALREADY_EXPIRED = """
            {
              "code": "CONFLICT",
              "message": "Voucher 9 is EXPIRED already"
            }""";

    // ---- staff: where GetMore stands, sync issues, issuances, syncs ------------------------------------------------

    public static final String STATUS_SIMULATED = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "mode": "SIMULATED",
                "ruleId": 43,
                "couponPrefix": "SIM-",
                "liveHost": null,
                "amountModel": "SIMULATED_PER_COUPON",
                "partialSpending": true,
                "staffLoanMerchant": {
                  "merchantCode": "getmore-groceries",
                  "voucherStore": "GETMORE"
                },
                "warnings": [],
                "issuances": {
                  "PENDING": 0,
                  "CALLING": 0,
                  "PARKED": 0,
                  "CANCELLING": 0
                },
                "openSyncIssues": 0,
                "blockedVouchers": 0,
                "heldVouchers": 0,
                "rule": {
                  "ruleId": 43,
                  "name": "InnBucks Staff Grocery (SIMULATED)",
                  "active": true,
                  "fromDate": null,
                  "toDate": null,
                  "simpleAction": "cart_fixed",
                  "discountAmount": 0.0000,
                  "couponType": "AUTO",
                  "useAutoGeneration": true,
                  "usesPerCoupon": 0,
                  "usesPerCustomer": 0,
                  "stopRulesProcessing": true,
                  "applyToShipping": false
                }
              }
            }""";

    public static final String STATUS_LIVE_RULE_UNREADABLE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "mode": "LIVE",
                "ruleId": 43,
                "couponPrefix": "INN-",
                "liveHost": "qa.getmore.syncrony.com",
                "amountModel": "NONE",
                "partialSpending": false,
                "staffLoanMerchant": {
                  "merchantCode": "getmore-groceries",
                  "voucherStore": "GETMORE"
                },
                "warnings": [
                  "LIVE reads GetMore but issues no voucher until GETMORE_LIVE_AMOUNT_MODEL is set"
                ],
                "issuances": {
                  "PENDING": 0,
                  "CALLING": 0,
                  "PARKED": 1,
                  "CANCELLING": 0
                },
                "openSyncIssues": 0,
                "blockedVouchers": 0,
                "heldVouchers": 0,
                "ruleError": "GETMORE_UNAVAILABLE: AUTH"
              }
            }""";

    public static final String STATUS_OFF = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "mode": "OFF",
                "ruleId": 43,
                "couponPrefix": "INN-",
                "liveHost": null,
                "amountModel": "NONE",
                "partialSpending": false,
                "staffLoanMerchant": {
                  "merchantCode": "getmore-groceries",
                  "voucherStore": "GETMORE"
                },
                "warnings": [
                  "GetMore is OFF: an accepted Staff Grocery Loan waits AWAITING_DISBURSEMENT with no voucher. Set GETMORE_MODE."
                ],
                "issuances": {
                  "PENDING": 0,
                  "CALLING": 0,
                  "PARKED": 0,
                  "CANCELLING": 0
                },
                "openSyncIssues": 0,
                "blockedVouchers": 0,
                "heldVouchers": 0
              }
            }""";

    // Rutendo's voucher 9 never had an issue; this is another simulated voucher (12, SGL-2026-000171), whose till
    // took more off it than it was worth.
    public static final String SYNC_ISSUES = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": 4,
                    "voucherId": 12,
                    "loanAccount": "SGL-2026-000171",
                    "kind": "OVERSPENT",
                    "blocking": true,
                    "externalRef": "order:21",
                    "detail": "order SIM000000021 spent 80.00, which would take the voucher to 230.00 spent of 150.00",
                    "firstSeenAt": "2026-10-14T11:02:44+02:00",
                    "lastSeenAt": "2026-10-14T11:15:09+02:00",
                    "clearedAt": null
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    private static final String ISSUANCE_164_HEAD = """
                {
                  "id": 12,
                  "staffLoanId": 164,
                  "loanReference": "SGL-2026-000164",""";

    private static final String ISSUANCE_164_ISSUED = ISSUANCE_164_HEAD + """

                  "status": "ISSUED",
                  "storeMode": "SIMULATED",
                  "couponPrefix": "SIM-K7Q2M3XW-",
                  "attempts": 1,
                  "generateCalls": 1,
                  "generateAllowance": 1,
                  "mintedCodes": 0,
                  "lastOutcome": "ISSUED",
                  "lastError": null,
                  "nextAttemptAt": "2026-10-14T10:20:05+02:00",
                  "voucherId": 9,
                  "createdAt": "2026-10-14T10:20:05+02:00",
                  "updatedAt": "2026-10-14T10:20:07+02:00"
                }""";

    public static final String ISSUANCES = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [""" + ISSUANCE_164_ISSUED + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    // Had the generate at acceptance lost its answer and the coupon never shown up on the prefix, the issuance would
    // have parked GENERATED_NOT_FOUND; Credit, told by GetMore that nothing was made, allows one more.
    public static final String ISSUANCES_PARKED = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [""" + ISSUANCE_164_HEAD + """

                  "status": "PARKED",
                  "storeMode": "SIMULATED",
                  "couponPrefix": "SIM-K7Q2M3XW-",
                  "attempts": 2,
                  "generateCalls": 1,
                  "generateAllowance": 1,
                  "mintedCodes": 0,
                  "lastOutcome": "GENERATED_NOT_FOUND",
                  "lastError": null,
                  "nextAttemptAt": "2026-10-14T10:20:35+02:00",
                  "voucherId": null,
                  "createdAt": "2026-10-14T10:20:05+02:00",
                  "updatedAt": "2026-10-14T10:21:02+02:00"
                }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String RETRY_REQUEST = """
            {
              "reason": "GetMore confirmed no coupon was made on the prefix",
              "generateAgain": true
            }""";

    static final String STAFF_LOAN_164_HEAD = """
                {
                  "id": 164,
                  "reference": "SGL-2026-000164",
                  "offerId": 41,
                  "staffMemberId": 9,
                  "employeeNumber": "E1064",
                  "fullName": "Rutendo Chari",
                  "msisdn": "****0064",
                  "grade": "C4",
                  "merchantCode": "getmore-groceries",
                  "merchantName": "GetMore Groceries",
                  "amount": 150.00,
                  "currency": "USD",
                  "interestRate": 0.0000,
                  "totalRepayable": 150.00,
                  "dueDate": "2026-11-20",
                  "unredeemedVoucherTreatment": "DEBT_STANDS",""";

    public static final String RETRIED = """
            {
              "code": "OK",
              "message": "The voucher of SGL-2026-000164 is issued",
              "data": {
                "issuance":""" + ISSUANCE_164_HEAD + """

                  "status": "ISSUED",
                  "storeMode": "SIMULATED",
                  "couponPrefix": "SIM-K7Q2M3XW-",
                  "attempts": 3,
                  "generateCalls": 2,
                  "generateAllowance": 2,
                  "mintedCodes": 0,
                  "lastOutcome": "ISSUED",
                  "lastError": null,
                  "nextAttemptAt": "2026-10-14T10:24:30+02:00",
                  "voucherId": 9,
                  "createdAt": "2026-10-14T10:20:05+02:00",
                  "updatedAt": "2026-10-14T10:24:31+02:00"
                },
                "loan":""" + STAFF_LOAN_164_HEAD + """

                  "status": "DISBURSED",
                  "inArrears": false,
                  "acceptedAt": "2026-10-14T10:20:05+02:00",
                  "disbursedAt": "2026-10-14T10:24:31+02:00",
                  "disbursementReference": "GETMORE-SIM-1207",
                  "settledAt": null,
                  "cancelledAt": null,
                  "cancelledBy": null,
                  "cancellationReason": null,
                  "employmentFlag": null,
                  "arrearsOverrideId": null
                }
              }
            }""";

    public static final String RETRY_REASON_MISSING = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "reason": "reason is required"
              }
            }""";

    public static final String RETRY_NOT_AWAITING = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2026-000164 is DISBURSED; only a loan awaiting its voucher can be issued one"
            }""";

    public static final String RETRY_PAST_DUE = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2026-000164 reached its payday cut-off (2026-11-20) without a voucher: cancel the loan"
            }""";

    public static final String ISSUANCE_IN_PROGRESS = """
            {
              "code": "VOUCHER_ISSUANCE_IN_PROGRESS",
              "message": "A voucher for SGL-2026-000164 is being issued right now; try again in a minute."
            }""";

    public static final String GETMORE_OFF = """
            {
              "code": "GETMORE_UNAVAILABLE",
              "message": "GetMore unavailable (OFF): GetMore is OFF on this host (GETMORE_MODE): no voucher can be issued"
            }""";

    public static final String SYNCED = """
            {
              "code": "OK",
              "message": "Voucher 9 synced with GetMore",
              "data": {
                "outcome": "SYNCED",
                "spendsRecorded": 0,
                "refundsRecorded": 1,
                "heldAmount": 0.00,
                "liveOrdersOnCoupon": 2,
                "issues": [],
                "couponWithdrawn": false,
                "voucher":""" + VOUCHER_9_AFTER_REFUND + """

              }
            }""";

    public static final String SYNC_NOT_GETMORE = """
            {
              "code": "CONFLICT",
              "message": "Voucher 7 is not a GetMore voucher of this host's store mode"
            }""";

    public static final String SYNC_UNAVAILABLE = """
            {
              "code": "GETMORE_UNAVAILABLE",
              "message": "GetMore unavailable (READ_FAILED): Cannot read voucher 9 at GetMore right now; try again."
            }""";

    public static final String SYNC_BUSY = """
            {
              "code": "GETMORE_UNAVAILABLE",
              "message": "GetMore unavailable (BUSY): Another sync of voucher 9 is running; try again."
            }""";

    // ---- cancelling a loan paid out as a GetMore voucher ----------------------------------------------------------

    public static final String CANCELLED_WITHDRAWN = """
            {
              "code": "OK",
              "message": "Cancelled. The voucher's coupon is withdrawn at GetMore.",
              "data":""" + STAFF_LOAN_164_HEAD + """

                  "status": "CANCELLED",
                  "inArrears": false,
                  "acceptedAt": "2026-10-14T10:20:05+02:00",
                  "disbursedAt": "2026-10-14T10:20:07+02:00",
                  "disbursementReference": "GETMORE-SIM-1207",
                  "settledAt": null,
                  "cancelledAt": "2026-10-14T10:26:40+02:00",
                  "cancelledBy": "cmanager",
                  "cancellationReason": "Accepted in error: the borrower asked to cancel before spending",
                  "employmentFlag": null,
                  "arrearsOverrideId": null
                }
            }""";

    public static final String CANCEL_USED = """
            {
              "code": "CONFLICT",
              "message": "The voucher of SGL-2026-000164 has been used (USD 45.50 spent, USD 0.00 held at the till); a loan whose voucher was used cannot be cancelled."
            }""";

    public static final String CANCEL_SYNC_ISSUE = """
            {
              "code": "CONFLICT",
              "message": "Voucher 9 has an open GetMore sync issue (OVERSPENT); resolve it before cancelling."
            }""";

    public static final String CANCEL_RACED = """
            {
              "code": "CONFLICT",
              "message": "A spend of USD 20.00 reached the voucher while cancelling; it has been stopped at GetMore and the loan stands."
            }""";

    public static final String CANCEL_REJECTED = """
            {
              "code": "GETMORE_REJECTED",
              "message": "GetMore DELETE_COUPONS rejected (INVALID): GetMore did not withdraw the coupon of voucher 9 (****8B1C); nothing was changed in loans"
            }""";

    public static final String CANCEL_OUTCOME_UNKNOWN = """
            {
              "code": "GETMORE_OUTCOME_UNKNOWN",
              "message": "GetMore DELETE_COUPONS: the outcome is unknown: the request was sent and its answer was lost. Repeat the request: it is safe"
            }""";

    public static final String CANCEL_UNAVAILABLE = """
            {
              "code": "GETMORE_UNAVAILABLE",
              "message": "GetMore unavailable (READ_FAILED): The coupon is withdrawn at GetMore; repeat the request to finish cancelling: it is safe."
            }""";

    public static final String VOUCHER_CANCEL_GETMORE = """
            {
              "code": "CONFLICT",
              "message": "Voucher 9 is the GetMore coupon of loan SGL-2026-000164: cancel the loan instead (POST /lending/v1/staff-loans/164/cancel)"
            }""";

    /**
     * Voucher 9 as staff open it after the refund: its two sales and the credit memo, each read back by a sync of the
     * simulated store (GetMore's own time as redeemedAt or refundedAt, recordedAt when loans read it), and no open sync
     * issue.
     */
    public static final String VOUCHER_9_DETAIL = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "voucher":""" + VOUCHER_9_AFTER_REFUND + """
            ,
                "redemptions": [
                  {
                    "id": 44,
                    "merchantReference": "GMS:O:12:4550",
                    "amount": 45.50,
                    "balanceAfter": 104.50,
                    "outletId": "store-2",
                    "outletName": "GetMore Avondale",
                    "redeemedBy": "getmore-simulator",
                    "redeemedAt": "2026-10-14T10:31:02+02:00",
                    "source": "GETMORE_SYNC",
                    "externalOrderNumber": "SIM000000012",
                    "recordedAt": "2026-10-14T10:31:02+02:00"
                  },
                  {
                    "id": 45,
                    "merchantReference": "GMS:O:13:5450",
                    "amount": 54.50,
                    "balanceAfter": 50.00,
                    "outletId": "store-2",
                    "outletName": "GetMore Avondale",
                    "redeemedBy": "getmore-simulator",
                    "redeemedAt": "2026-10-14T10:35:40+02:00",
                    "source": "GETMORE_SYNC",
                    "externalOrderNumber": "SIM000000013",
                    "recordedAt": "2026-10-14T10:37:15+02:00"
                  }
                ],
                "deliveries": [
                  {
                    "id": 21,
                    "channel": "WHATSAPP",
                    "recipient": "****0064",
                    "template": "VOUCHER_ISSUED_GETMORE",
                    "templateVersion": 1,
                    "status": "SENT",
                    "gatewayReference": null,
                    "failureReason": null,
                    "requestedBy": "system",
                    "attemptedAt": "2026-10-14T10:20:08+02:00"
                  }
                ],
                "refunds": [
                  {
                    "id": 3,
                    "merchantReference": "GMS:CM:3",
                    "creditMemoNumber": "SIMCM00000003",
                    "externalOrderId": 12,
                    "amount": 10.00,
                    "balanceAfter": 60.00,
                    "refundedAt": "2026-10-14T10:39:58+02:00",
                    "recordedAt": "2026-10-14T10:39:58+02:00",
                    "recordedBy": "getmore-simulator"
                  }
                ],
                "syncIssues": []
              }
            }""";

    /**
     * Staging's settlement report for 14 October so far (before the simulator expired voucher 9): it was issued, both
     * its sales and the refund were read back, each on the day loans read it with GetMore's own time as occurredAt,
     * and the report says simulated, since only a SIMULATED host reports simulated vouchers.
     */
    public static final String SETTLEMENT_REPORT_GETMORE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "date": "2026-10-14",
                "generatedAt": "2026-10-14T10:42:00+02:00",
                "totals": [
                  {
                    "currency": "USD",
                    "issuedCount": 1,
                    "issuedValue": 150.00,
                    "redemptionCount": 2,
                    "redeemedValue": 100.00,
                    "refundCount": 1,
                    "refundedValue": 10.00,
                    "netRedeemedValue": 90.00,
                    "cancelledCount": 0,
                    "cancelledValue": 0.00,
                    "expiredCount": 0,
                    "expiredUnredeemedValue": 0.00
                  }
                ],
                "byMerchant": [
                  {
                    "merchantCode": "getmore-groceries",
                    "merchantName": "GetMore Groceries",
                    "currency": "USD",
                    "issuedCount": 1,
                    "issuedValue": 150.00,
                    "redemptionCount": 2,
                    "redeemedValue": 100.00,
                    "refundCount": 1,
                    "refundedValue": 10.00,
                    "netRedeemedValue": 90.00,
                    "cancelledCount": 0,
                    "cancelledValue": 0.00,
                    "expiredCount": 0,
                    "expiredUnredeemedValue": 0.00
                  }
                ],
                "redemptionsByOutlet": [
                  {
                    "merchantCode": "getmore-groceries",
                    "outletId": "store-2",
                    "outletName": "GetMore Avondale",
                    "currency": "USD",
                    "redemptionCount": 2,
                    "redeemedValue": 100.00,
                    "refundCount": 1,
                    "refundedValue": 10.00,
                    "netRedeemedValue": 90.00
                  }
                ],
                "lines": [
                  {
                    "event": "ISSUED",
                    "at": "2026-10-14T10:20:07+02:00",
                    "voucherId": 9,
                    "merchantCode": "getmore-groceries",
                    "maskedCode": "****8B1C",
                    "loanAccount": "SGL-2026-000164",
                    "customerReference": "E1064",
                    "currency": "USD",
                    "amount": 150.00,
                    "outletId": null,
                    "outletName": null,
                    "merchantReference": null
                  },
                  {
                    "event": "REDEEMED",
                    "at": "2026-10-14T10:31:02+02:00",
                    "occurredAt": "2026-10-14T10:31:02+02:00",
                    "voucherId": 9,
                    "merchantCode": "getmore-groceries",
                    "maskedCode": "****8B1C",
                    "loanAccount": "SGL-2026-000164",
                    "customerReference": "E1064",
                    "currency": "USD",
                    "amount": 45.50,
                    "outletId": "store-2",
                    "outletName": "GetMore Avondale",
                    "merchantReference": "GMS:O:12:4550"
                  },
                  {
                    "event": "REDEEMED",
                    "at": "2026-10-14T10:37:15+02:00",
                    "occurredAt": "2026-10-14T10:35:40+02:00",
                    "voucherId": 9,
                    "merchantCode": "getmore-groceries",
                    "maskedCode": "****8B1C",
                    "loanAccount": "SGL-2026-000164",
                    "customerReference": "E1064",
                    "currency": "USD",
                    "amount": 54.50,
                    "outletId": "store-2",
                    "outletName": "GetMore Avondale",
                    "merchantReference": "GMS:O:13:5450"
                  },
                  {
                    "event": "REFUNDED",
                    "at": "2026-10-14T10:39:58+02:00",
                    "occurredAt": "2026-10-14T10:39:58+02:00",
                    "voucherId": 9,
                    "merchantCode": "getmore-groceries",
                    "maskedCode": "****8B1C",
                    "loanAccount": "SGL-2026-000164",
                    "customerReference": "E1064",
                    "currency": "USD",
                    "amount": 10.00,
                    "outletId": "store-2",
                    "outletName": "GetMore Avondale",
                    "merchantReference": "GMS:CM:3"
                  }
                ],
                "simulated": true
              }
            }""";

    public static final String VOUCHER_9_CODE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "voucherId": 9,
                "code": "SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C",
                "scanValue": "SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C"
              }
            }""";
}
