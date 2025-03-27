// This file is executed before running tests
import '@testing-library/jest-dom';

// Add TextEncoder/TextDecoder for jest environment
if (typeof TextEncoder === 'undefined') {
  global.TextEncoder = require('util').TextEncoder;
  global.TextDecoder = require('util').TextDecoder;
}

// Mock window.location.origin
Object.defineProperty(window, 'location', {
  writable: true,
  value: { origin: 'http://localhost:3000' }
}); 