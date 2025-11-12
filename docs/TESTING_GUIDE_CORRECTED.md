# Corrected Testing Guide - For Your Implementation

## 🎯 Understanding Your Implementation

Your implementation uses **filenames directly**, not separate file IDs:

### Upload Response
```
✅ CORRECT: "Uploaded 1 chunks for redad.jpg"
❌ WRONG:   {"fileId": "abc123", "chunks": [...]}
```

### Download URL
```
✅ CORRECT: http://localhost:8080/files/redad.jpg
❌ WRONG:   http://localhost:8080/files/abc123
```

---

## 📝 How Your System Works

### 1. Upload Process
```
POST http://localhost:8080/files
Body: multipart/form-data with file

Response: "Uploaded 1 chunks for redad.jpg"
```

### 2. Storage Process
Your system splits the file into chunks:
- `redad.jpg.part0`
- `redad.jpg.part1`
- `redad.jpg.part2`
- etc.

Each chunk is stored across multiple storage nodes (replication).

### 3. Download Process
```
GET http://localhost:8080/files/redad.jpg

Response: Binary file data
```

The gateway:
1. Reads `redad.jpg.part0` from storage nodes
2. Reads `redad.jpg.part1` from storage nodes
3. Merges all parts back into the original file
4. Returns the complete file

---

## 🧪 Testing with Postman

### Step 1: Import the Updated Collection

1. Open Postman
2. Click **Import**
3. Select `postman_collection_updated.json`
4. Click **Import**

### Step 2: Test Upload

#### Request
```
POST http://localhost:8080/files
Body: form-data
  file: [Select redad.jpg or any file]
```

#### Expected Response
```
Status: 200 OK
Body: "Uploaded 1 chunks for redad.jpg"
```

**Note:** The number of chunks depends on file size (1MB per chunk).

### Step 3: Test Download

#### Request
```
GET http://localhost:8080/files/redad.jpg
```

#### Expected Response
```
Status: 200 OK
Body: [Binary file data]
Content-Type: application/octet-stream
```

**Important:** Use the **exact filename** you uploaded (case-sensitive!)

### Step 4: Check Storage Nodes

After uploading, check which nodes have the chunks:

```
GET http://localhost:9001/chunks
GET http://localhost:9002/chunks
GET http://localhost:9003/chunks
```

You should see chunks like:
```json
[
  "redad.jpg.part0",
  "redad.jpg.part1",
  ...
]
```

---

## 🔍 Troubleshooting Your Issue

### Problem: "file not from fileId"

**Cause:** You're using a fileId instead of the filename.

**Solution:** Use the exact filename from the upload response:

```
❌ WRONG: http://localhost:8080/files/abc123
✅ CORRECT: http://localhost:8080/files/redad.jpg
```

### Problem: File not found

**Possible causes:**

1. **Filename mismatch** - Check exact spelling and case
   ```
   ❌ redad.JPG
   ✅ redad.jpg
   ```

2. **Chunks not stored** - Check if chunks exist on storage nodes
   ```
   GET http://localhost:9001/chunks
   ```

3. **Not enough storage nodes** - Need at least W=2 nodes up
   ```
   GET http://localhost:8080/membership/nodes/up
   ```

---

## 📊 Complete Testing Workflow

### Test 1: Basic Upload and Download

#### Step 1: Upload
```
POST http://localhost:8080/files
Body: Select a test file (e.g., redad.jpg)

Response: "Uploaded 1 chunks for redad.jpg"
```

#### Step 2: Verify Chunks on Storage Nodes
```
GET http://localhost:9001/chunks
Response: ["redad.jpg.part0"]

GET http://localhost:9002/chunks
Response: ["redad.jpg.part0"]

GET http://localhost:9003/chunks
Response: ["redad.jpg.part0"]
```

#### Step 3: Download
```
GET http://localhost:8080/files/redad.jpg

Response: Binary file (should be identical to uploaded file)
```

### Test 2: Large File (Multiple Chunks)

#### Step 1: Upload a large file (>1MB)
```
POST http://localhost:8080/files
Body: Select a large file (e.g., 3MB image)

Response: "Uploaded 3 chunks for large-image.jpg"
```

#### Step 2: Check chunks
```
GET http://localhost:9001/chunks

Response: [
  "large-image.jpg.part0",
  "large-image.jpg.part1",
  "large-image.jpg.part2"
]
```

#### Step 3: Download
```
GET http://localhost:8080/files/large-image.jpg

Response: Complete file (3MB)
```

### Test 3: Check Membership

```
GET http://localhost:8080/membership/nodes

Response: [
  {
    "nodeId": "storage-node-abc123",
    "host": "localhost",
    "port": 9001,
    "status": "UP",
    "lastSeen": "2024-01-15T10:30:00Z"
  },
  {
    "nodeId": "storage-node-def456",
    "host": "localhost",
    "port": 9002,
    "status": "UP",
    "lastSeen": "2024-01-15T10:30:00Z"
  },
  {
    "nodeId": "storage-node-ghi789",
    "host": "localhost",
    "port": 9003,
    "status": "UP",
    "lastSeen": "2024-01-15T10:30:00Z"
  }
]
```

### Test 4: Direct Chunk Operations

#### Write directly to a storage node
```
PUT http://localhost:9001/chunks/my-test-data
Content-Type: application/octet-stream
Body: "Hello from Node 1!"

Response: "OK"
```

#### Read from the same node
```
GET http://localhost:9001/chunks/my-test-data

Response: "Hello from Node 1!"
```

#### Read from another node (if replicated)
```
GET http://localhost:9002/chunks/my-test-data

Response: "Hello from Node 1!" (if replicated)
or 404 (if not replicated)
```

---

## 🎯 Testing Checklist

### Basic Operations
- [ ] Upload a file via Gateway
- [ ] Verify upload response shows correct filename
- [ ] Download file using the exact filename
- [ ] Verify downloaded file matches original

### Storage Nodes
- [ ] Check chunks on Node 1
- [ ] Check chunks on Node 2
- [ ] Check chunks on Node 3
- [ ] Verify chunks are replicated

### Membership
- [ ] Check all nodes
- [ ] Check up nodes
- [ ] Verify all nodes are UP

### Fault Tolerance
- [ ] Stop one storage node
- [ ] Wait 5 seconds
- [ ] Check nodes - one should be DOWN
- [ ] Restart the node
- [ ] Check nodes - all should be UP again

---

## 💡 Tips for Testing

### 1. Use Environment Variables in Postman

Create an environment with:
```
uploaded_filename = redad.jpg
```

Then use in requests:
```
GET http://localhost:8080/files/{{uploaded_filename}}
```

### 2. Save Responses

After uploading, save the filename from the response:
```javascript
// In Postman Tests tab
var responseText = pm.response.text();
var match = responseText.match(/Uploaded \d+ chunks for (.+)/);
if (match) {
    pm.environment.set('uploaded_filename', match[1]);
}
```

### 3. Use Collection Runner

Create a test sequence:
1. Upload file
2. Wait 1 second
3. Download file
4. Verify file size matches

---

## 🔧 Common Issues and Solutions

### Issue 1: File Not Found
```
Status: 404 Not Found
```

**Solutions:**
- Check exact filename spelling
- Verify chunks exist on storage nodes
- Ensure at least W=2 nodes are UP

### Issue 2: Service Unavailable
```
Status: 503 Service Unavailable
Body: "Not enough healthy nodes: 1 (need 2)"
```

**Solutions:**
- Start more storage nodes
- Check node status: `GET /membership/nodes/up`
- Wait for nodes to register

### Issue 3: Upload Fails
```
Status: 503 Service Unavailable
Body: "W quorum failed: 1/3"
```

**Solutions:**
- Check storage nodes are running
- Check network connectivity
- Increase timeout in GatewayController

---

## 📚 Your System Architecture

```
┌─────────────┐
│   Gateway   │  Port 8080
│  (IntelliJ) │
└──────┬──────┘
       │
       ├─────────────┬─────────────┐
       │             │             │
┌──────▼──────┐ ┌───▼──────┐ ┌───▼──────┐
│ Storage 1   │ │ Storage 2 │ │ Storage 3 │
│ Port 9001   │ │ Port 9002 │ │ Port 9003 │
│ (IntelliJ)  │ │ (IntelliJ) │ │ (IntelliJ) │
└─────────────┘ └───────────┘ └───────────┘
```

### How It Works

1. **Upload:**
   - Gateway receives file
   - Splits into chunks (1MB each)
   - Writes each chunk to W=2 nodes (replication)
   - Returns success message with filename

2. **Download:**
   - Gateway receives filename
   - Reads chunks from R=2 nodes (quorum read)
   - Merges chunks back into file
   - Returns complete file

3. **Membership:**
   - Each storage node sends heartbeat every 1 second
   - Gateway tracks node status (UP/DOWN)
   - Repair controller handles node failures

---

## ✅ Summary

### Your Implementation Uses:
- ✅ **Filenames** (not fileIds)
- ✅ **Text responses** (not JSON)
- ✅ **Chunk naming:** `filename.part0`, `filename.part1`, etc.
- ✅ **W=2, R=2** quorum (write to 2, read from 2)

### Correct URLs:
```
Upload:   POST http://localhost:8080/files
Download: GET http://localhost:8080/files/redad.jpg
Chunks:   GET http://localhost:9001/chunks
Nodes:    GET http://localhost:8080/membership/nodes
```

### Expected Responses:
```
Upload:   "Uploaded 1 chunks for redad.jpg"
Download: [Binary file data]
Chunks:   ["redad.jpg.part0"]
Nodes:    [{nodeId: "...", status: "UP", ...}]
```

**Now you're ready to test! Import `postman_collection_updated.json` and follow the workflow above.** 🚀

