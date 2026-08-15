# SMSfromCSV MMS v1.5

This branch adds CSV + ZIP image MMS sending while preserving normal SMS, personalised text, the 500-recipient safety cap, and multi-SIM routing.

The parallel-install build uses package ID `com.blankmediator.smsfromcsv.mms` and launcher label **SMSfromCSV MMS**, so it can coexist with the original app.

## ZIP format

```text
batch.zip
├── recipients.csv
└── images/
    └── invite-jane.jpg
```

Example `recipients.csv`:

```csv
name,phone,sim,image,message
Jane Smith,+61416950104,SIM1,images/invite-jane.jpg,"Hi {name}, here is your invitation."
```

A blank `image` cell sends SMS. A nonblank `image` cell sends image MMS using that file from the ZIP. Images are resized/compressed before MMS composition to fit carrier limits.

Build settings for this preview are Android API 36, version code 7, version name 1.5.0.
