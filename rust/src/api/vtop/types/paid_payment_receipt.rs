use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaidPaymentReceipt {
    pub receipt_number: String,
    pub date: String,
    pub amount: String,
    pub campus_code: String,
    pub payment_status: String, // Always "Paid"
    pub receipt_no: String,     // The value from doDuplicateReceipt('...')
}
