package zw.co.innbucks.loans.web;

/**
 * Example bodies for the voucher endpoints, as one story: voucher 7 (Chipo Banda, USD 300.00, issued 6 October) was
 * sent by SMS and spent down to USD 120.00 at GetMore Avondale on 8 October; voucher 8 (Nyasha Dube, USD 250.00, issued
 * 8 October) could not be sent on either channel. Their codes carry real Damm check digits.
 */
public final class VoucherApiExamples {

    /** Voucher 7's code, digits only: what a till or QR code carries. */
    public static final String CODE_7 = "4829150673318406";

    private static final String VOUCHER_7 = """
                {
                  "id": 7,
                  "product": "STAFF_GROCERY_LOAN",
                  "loanAccount": "SGL-2026-000143",
                  "disbursementReference": "BRNET-20261006-0007",
                  "customerReference": "E1012",
                  "customerName": "Chipo Banda",
                  "customerMsisdn": "****6789",
                  "maskedCode": "**** **** **** 8406",
                  "faceValue": 300.00,
                  "redeemedAmount": 180.00,
                  "balance": 120.00,
                  "currency": "USD",
                  "issuedAt": "2026-10-06T09:14:22+02:00",
                  "expiresAt": "2026-11-05T23:59:59+02:00",
                  "status": "PARTIALLY_REDEEMED",
                  "lastRedeemedAt": "2026-10-08T17:42:10+02:00",
                  "lastRedeemedOutlet": "GetMore Avondale",
                  "cancelledBy": null,
                  "cancelledAt": null,
                  "cancellationReason": null,
                  "deliveryStatus": "SENT",
                  "deliveredChannel": "SMS",
                  "deliveryUpdatedAt": "2026-10-06T09:14:23+02:00"
                }""";

    private static final String VOUCHER_8_HEAD = """
                {
                  "id": 8,
                  "product": "STAFF_GROCERY_LOAN",
                  "loanAccount": "SGL-2026-000151",
                  "disbursementReference": "BRNET-20261008-0002",
                  "customerReference": "E1001",
                  "customerName": "Nyasha Dube",
                  "customerMsisdn": "****6983",
                  "maskedCode": "**** **** **** 2151",
                  "faceValue": 250.00,
                  "redeemedAmount": 0.00,
                  "balance": 250.00,
                  "currency": "USD",
                  "issuedAt": "2026-10-08T08:05:41+02:00",
                  "expiresAt": "2026-11-07T23:59:59+02:00",
                  "lastRedeemedAt": null,
                  "lastRedeemedOutlet": null,
                  "deliveredChannel": null,""";

    private static final String VOUCHER_8 = VOUCHER_8_HEAD + """

                  "status": "ISSUED",
                  "cancelledBy": null,
                  "cancelledAt": null,
                  "cancellationReason": null,
                  "deliveryStatus": "FAILED",
                  "deliveryUpdatedAt": "2026-10-08T08:05:52+02:00"
                }""";

    public static final String VOUCHERS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [""" + VOUCHER_8 + "," + VOUCHER_7 + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 2,
                "totalPages": 1
              }
            }""";

    public static final String VOUCHER_7_DETAIL = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "voucher":""" + VOUCHER_7 + """
            ,
                "redemptions": [
                  {
                    "id": 31,
                    "merchantReference": "GM-POS-88412",
                    "amount": 180.00,
                    "balanceAfter": 120.00,
                    "outletId": "GM-AVD-01",
                    "outletName": "GetMore Avondale",
                    "redeemedBy": "getmore-pos",
                    "redeemedAt": "2026-10-08T17:42:10+02:00"
                  }
                ],
                "deliveries": [
                  {
                    "id": 11,
                    "channel": "SMS",
                    "recipient": "****6789",
                    "template": "VOUCHER_ISSUED",
                    "templateVersion": 1,
                    "status": "SENT",
                    "gatewayReference": "LOANS-VCH-5b1d0c7e-2f44-4a8e-9a51-0c3e8f2d6b19",
                    "failureReason": null,
                    "requestedBy": "system",
                    "attemptedAt": "2026-10-06T09:14:23+02:00"
                  }
                ]
              }
            }""";

    public static final String VOUCHER_7_CODE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "voucherId": 7,
                "code": "4829 1506 7331 8406",
                "scanValue": "4829150673318406"
              }
            }""";

    public static final String REASON_REQUEST = """
            {
              "reason": "Customer on the phone, cannot find the SMS"
            }""";

    public static final String CANCEL_REQUEST = """
            {
              "reason": "Payout reversed: paid to the wrong staff member"
            }""";

    public static final String VOUCHER_8_CANCELLED = """
            {
              "code": "OK",
              "message": "Voucher 8 cancelled",
              "data":""" + VOUCHER_8_HEAD + """

                  "status": "CANCELLED",
                  "cancelledBy": "credit1",
                  "cancelledAt": "2026-10-09T10:20:05+02:00",
                  "cancellationReason": "Payout reversed: paid to the wrong staff member",
                  "deliveryStatus": "FAILED",
                  "deliveryUpdatedAt": "2026-10-08T08:05:52+02:00"
                }
            }""";

    public static final String VOUCHER_8_RESENT = """
            {
              "code": "ACCEPTED",
              "message": "Voucher 8 queued to be sent again",
              "data":""" + VOUCHER_8_HEAD + """

                  "status": "ISSUED",
                  "cancelledBy": null,
                  "cancelledAt": null,
                  "cancellationReason": null,
                  "deliveryStatus": "PENDING",
                  "deliveryUpdatedAt": "2026-10-09T09:02:17+02:00"
                }
            }""";

    public static final String VALIDATION_REQUEST = """
            {
              "code": "4829 1506 7331 8406",
              "outletId": "GM-AVD-01"
            }""";

    public static final String VALIDATION = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "maskedCode": "**** **** **** 8406",
                "status": "PARTIALLY_REDEEMED",
                "redeemable": true,
                "faceValue": 300.00,
                "balance": 120.00,
                "currency": "USD",
                "expiresAt": "2026-11-05T23:59:59+02:00",
                "holderName": "Chipo B.",
                "partialRedemptionAllowed": true
              }
            }""";

    public static final String REDEMPTION_REQUEST = """
            {
              "code": "4829-1506-7331-8406",
              "amount": 180.00,
              "currency": "USD",
              "outletId": "GM-AVD-01",
              "outletName": "GetMore Avondale",
              "reference": "GM-POS-88412"
            }""";

    private static final String REDEMPTION_88412 = """
              "data": {
                "reference": "GM-POS-88412",
                "maskedCode": "**** **** **** 8406",
                "amount": 180.00,
                "balanceAfter": 120.00,
                "currency": "USD",
                "status": "PARTIALLY_REDEEMED",
                "outletId": "GM-AVD-01",
                "redeemedAt": "2026-10-08T17:42:10+02:00",""";

    public static final String REDEEMED = """
            {
              "code": "CREATED",
              "message": "Redeemed USD 180.00; USD 120.00 left",
            """ + REDEMPTION_88412 + """

                "replayed": false
              }
            }""";

    public static final String REDEEMED_REPLAYED = """
            {
              "code": "OK",
              "message": "Already redeemed under reference GM-POS-88412; nothing more was spent",
            """ + REDEMPTION_88412 + """

                "replayed": true
              }
            }""";

    public static final String SETTLEMENT_REPORT = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "date": "2026-10-08",
                "generatedAt": "2026-10-09T06:30:00+02:00",
                "totals": [
                  {
                    "currency": "USD",
                    "issuedCount": 1,
                    "issuedValue": 250.00,
                    "redemptionCount": 1,
                    "redeemedValue": 180.00,
                    "cancelledCount": 0,
                    "cancelledValue": 0.00,
                    "expiredCount": 0,
                    "expiredUnredeemedValue": 0.00
                  }
                ],
                "redemptionsByOutlet": [
                  {
                    "outletId": "GM-AVD-01",
                    "outletName": "GetMore Avondale",
                    "currency": "USD",
                    "redemptionCount": 1,
                    "redeemedValue": 180.00
                  }
                ],
                "lines": [
                  {
                    "event": "ISSUED",
                    "at": "2026-10-08T08:05:41+02:00",
                    "voucherId": 8,
                    "maskedCode": "**** **** **** 2151",
                    "loanAccount": "SGL-2026-000151",
                    "customerReference": "E1001",
                    "currency": "USD",
                    "amount": 250.00,
                    "outletId": null,
                    "outletName": null,
                    "merchantReference": null
                  },
                  {
                    "event": "REDEEMED",
                    "at": "2026-10-08T17:42:10+02:00",
                    "voucherId": 7,
                    "maskedCode": "**** **** **** 8406",
                    "loanAccount": "SGL-2026-000143",
                    "customerReference": "E1012",
                    "currency": "USD",
                    "amount": 180.00,
                    "outletId": "GM-AVD-01",
                    "outletName": "GetMore Avondale",
                    "merchantReference": "GM-POS-88412"
                  }
                ]
              }
            }""";

    public static final String UNAVAILABLE = """
            {
              "code": "VOUCHERS_UNAVAILABLE",
              "message": "Vouchers are not available: the voucher code keys are not configured"
            }""";

    public static final String NOT_FOUND_8 = """
            {
              "code": "NOT_FOUND",
              "message": "Voucher 8 not found"
            }""";

    private VoucherApiExamples() {
    }
}
