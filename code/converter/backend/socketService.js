/**
 * socketService.js — Wraps Socket.IO, broadcasts job events to all clients
 */

let io = null;

function init(socketIO) {
  io = socketIO;
  io.on('connection', (socket) => {
    console.log(`[socket] client connected: ${socket.id}`);
    socket.on('disconnect', () => {
      console.log(`[socket] client disconnected: ${socket.id}`);
    });
  });
}

function emit(event, data) {
  if (io) io.emit(event, data);
}

module.exports = { init, emit };
