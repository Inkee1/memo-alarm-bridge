package ipc

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/app"
	"github.com/Microsoft/go-winio"
	"io"
	"net"
	"net/http"
	"time"
)

func Request(ctx context.Context, dataDir string, body []byte) (app.CommandResult, error) {
	name, _, e := pipeIdentity(dataDir)
	if e != nil {
		return app.CommandResult{}, e
	}
	transport := &http.Transport{DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		return winio.DialPipeContext(ctx, name)
	}, DisableKeepAlives: true}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 20 * time.Second}
	req, e := http.NewRequestWithContext(ctx, "POST", "http://pipe/v1/command", bytes.NewReader(body))
	if e != nil {
		return app.CommandResult{}, e
	}
	req.Header.Set("Content-Type", "application/json")
	r, e := client.Do(req)
	if e != nil {
		return app.CommandResult{}, e
	}
	defer r.Body.Close()
	var result app.CommandResult
	e = json.NewDecoder(io.LimitReader(r.Body, 16*1024*1024)).Decode(&result)
	return result, e
}
