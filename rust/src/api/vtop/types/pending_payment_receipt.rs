use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingPaymentReceipt {
    pub s_no: String,
    pub fprefno: String,
    pub fees_heads: String,
    pub end_date: String,
    pub amount: String,
    pub fine: String,
    pub total_amount: String,
    pub payment_status: String, // Always "Unpaid"
}
