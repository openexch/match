package services

import (
	"encoding/binary"
	"fmt"
	"os"
	"strings"
	"sync"
)

// AeronCounters reads Aeron counters from the CnC (Command and Control) file
// Reference: https://github.com/real-logic/aeron/blob/master/aeron-client/src/main/java/io/aeron/CncFileDescriptor.java
type AeronCounters struct {
	mu sync.RWMutex
}

// CnC file header offsets (little-endian)
const (
	toDriverBufferLenOffset  = 4
	toClientsBufferLenOffset = 8
	counterMetadataLenOffset = 12
	counterValuesLenOffset   = 16
	cncHeaderLength          = 128 // Two cache lines
)

// Counter record constants
const (
	counterRecordAllocated = 1
	counterValueLength     = 128 // Each counter value is cache-line padded
	counterMetadataLength  = 512 // state(4) + typeId(4) + deadline(8) + key(112) + label(384)
	counterLabelOffset     = 128 // Label starts after key buffer
	maxLabelLength         = 380
)

// CounterData holds counter information for a node
type CounterData struct {
	CommitPosition int64 // Cluster commit position (real-time)
	SnapshotCount  int64 // Number of snapshots taken
	NodeRole       int64 // 0=follower, 1=candidate, 2=leader
}

func NewAeronCounters() *AeronCounters {
	return &AeronCounters{}
}

// GetNodeCounters reads counters for a specific node
func (ac *AeronCounters) GetNodeCounters(nodeId int) (*CounterData, error) {
	if nodeId < 0 || nodeId > 2 {
		return nil, fmt.Errorf("invalid nodeId: %d", nodeId)
	}

	// Get home directory for username pattern
	homeDir, _ := os.UserHomeDir()
	var username string
	if homeDir != "" {
		parts := strings.Split(homeDir, "/")
		if len(parts) > 0 {
			username = parts[len(parts)-1]
		}
	}
	if username == "" {
		username = os.Getenv("USER")
	}

	cncPath := fmt.Sprintf("/dev/shm/aeron-%s-%d-driver/cnc.dat", username, nodeId)

	// Open file
	f, err := os.Open(cncPath)
	if err != nil {
		return nil, fmt.Errorf("failed to open CnC file %s: %w", cncPath, err)
	}
	defer f.Close()

	return ac.readCounters(f)
}

func (ac *AeronCounters) readCounters(f *os.File) (*CounterData, error) {
	// Read header (first 64 bytes is enough for what we need)
	header := make([]byte, 64)
	if _, err := f.ReadAt(header, 0); err != nil {
		return nil, fmt.Errorf("failed to read header: %w", err)
	}

	// Parse header
	toDriverLen := binary.LittleEndian.Uint32(header[toDriverBufferLenOffset:])
	toClientsLen := binary.LittleEndian.Uint32(header[toClientsBufferLenOffset:])
	metadataLen := binary.LittleEndian.Uint32(header[counterMetadataLenOffset:])
	valuesLen := binary.LittleEndian.Uint32(header[counterValuesLenOffset:])

	// Calculate buffer offsets
	// Layout: [header 128B][toDriver][toClients][counterMetadata][counterValues][errorLog]
	metadataOffset := int64(cncHeaderLength) + int64(toDriverLen) + int64(toClientsLen)
	valuesOffset := metadataOffset + int64(metadataLen)

	// Calculate number of counters
	numCounters := int(valuesLen) / counterValueLength

	data := &CounterData{
		CommitPosition: -1,
		SnapshotCount:  -1,
		NodeRole:       -1,
	}

	// Read metadata buffer to find counter labels
	metadataBuf := make([]byte, metadataLen)
	if _, err := f.ReadAt(metadataBuf, metadataOffset); err != nil {
		return nil, fmt.Errorf("failed to read metadata: %w", err)
	}

	// Read values buffer
	valuesBuf := make([]byte, valuesLen)
	if _, err := f.ReadAt(valuesBuf, valuesOffset); err != nil {
		return nil, fmt.Errorf("failed to read values: %w", err)
	}

	// Scan counters
	for i := 0; i < numCounters; i++ {
		metaOffset := i * counterMetadataLength

		// Check if counter is allocated (first 4 bytes = state)
		if metaOffset+4 > len(metadataBuf) {
			break
		}
		state := binary.LittleEndian.Uint32(metadataBuf[metaOffset:])
		if state != counterRecordAllocated {
			continue
		}

		// Read label length (at offset 128 in metadata record)
		labelLenOffset := metaOffset + counterLabelOffset
		if labelLenOffset+4 > len(metadataBuf) {
			continue
		}
		labelLen := int(binary.LittleEndian.Uint32(metadataBuf[labelLenOffset:]))
		if labelLen <= 0 || labelLen > maxLabelLength {
			continue
		}

		// Read label string
		labelStart := labelLenOffset + 4
		if labelStart+labelLen > len(metadataBuf) {
			continue
		}
		label := string(metadataBuf[labelStart : labelStart+labelLen])

		// Read value (8-byte int64 at start of value record)
		valueOffset := i * counterValueLength
		if valueOffset+8 > len(valuesBuf) {
			continue
		}
		value := int64(binary.LittleEndian.Uint64(valuesBuf[valueOffset:]))

		// Match counters we care about
		if strings.Contains(label, "Cluster commit-pos") {
			data.CommitPosition = value
		} else if strings.Contains(label, "Cluster snapshot count") {
			data.SnapshotCount = value
		} else if strings.Contains(label, "Cluster node role") {
			data.NodeRole = value
		}
	}

	return data, nil
}
