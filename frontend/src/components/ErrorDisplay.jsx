import React from 'react'
import { Header, Button, Icon, Segment } from 'semantic-ui-react'

// CSS for animation
const errorCardAnimation = `
@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.error-card {
  animation: fadeInUp 0.4s ease-out forwards;
}

.pulse-icon {
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.05);
  }
  100% {
    transform: scale(1);
  }
}

.retry-button:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.15) !important;
}
`;

// Function to get error details based on error message
const getErrorDetails = (errorMessage) => {
  if (errorMessage === 'Failed to fetch') {
    return {
      title: 'Connection Error',
      description: 'The service is currently unavailable. This could be a temporary issue.',
      icon: 'server',
      color: '#E74C3C',
      suggestions: [
        'Try again in a few moments',
        'Check your internet connection',
        'Refresh the page'
      ]
    }
  } else if (errorMessage.includes('timeout')) {
    return {
      title: 'Request Timeout',
      description: 'The connection to our keyboard catalog timed out. This might be due to temporary service issues.',
      icon: 'clock outline',
      color: '#F39C12',
      suggestions: [
        'Try again in a few moments',
        'Check your internet connection',
        'Refresh the page and try again'
      ]
    }
  } else {
    return {
      title: 'Something Went Wrong',
      description: 'We\'re experiencing some technical difficulties. This is usually temporary.',
      icon: 'exclamation triangle',
      color: '#E74C3C',
      suggestions: [
        'Try refreshing the page',
        'Check your internet connection',
        'Try again later'
      ]
    }
  }
}

const ErrorDisplay = ({ errorMessage, onRetry }) => {
  const errorInfo = getErrorDetails(errorMessage);
  
  return (
    <>
      {/* Add style tag for animations */}
      <style>{errorCardAnimation}</style>
      
      <Segment placeholder raised className="error-card" style={{ 
        marginBottom: '2rem', 
        borderRadius: '10px',
        boxShadow: '0 6px 20px rgba(0, 0, 0, 0.1)',
        background: 'linear-gradient(to right, rgba(255,255,255,0.95), rgba(249,249,249,0.95))',
        border: 'none',
        padding: '2rem'
      }}>
        <div style={{ textAlign: 'center' }}>
          <Icon name={errorInfo.icon} size="huge" className="pulse-icon" style={{ color: errorInfo.color, marginBottom: '1rem', opacity: 0.8 }} />
          <Header as='h2' style={{ margin: '0.5rem 0', color: '#333' }}>
            {errorInfo.title}
          </Header>
          <p style={{ fontSize: '1rem', color: '#666', maxWidth: '500px', margin: '1rem auto' }}>
            {errorInfo.description}
          </p>
          
          {/* Suggestions section */}
          <div style={{ 
            background: 'rgba(240, 240, 240, 0.5)', 
            padding: '1rem', 
            borderRadius: '8px', 
            maxWidth: '500px', 
            margin: '1.5rem auto', 
            textAlign: 'left' 
          }}>
            <p style={{ fontWeight: 'bold', marginBottom: '0.5rem', color: '#555' }}>Try these suggestions:</p>
            <ul style={{ paddingLeft: '1.5rem', margin: '0.5rem 0' }}>
              {errorInfo.suggestions.map((suggestion, index) => (
                <li key={index} style={{ margin: '0.5rem 0', color: '#555' }}>{suggestion}</li>
              ))}
            </ul>
          </div>
          
          {onRetry && (
            <Button
              primary
              className="retry-button"
              style={{ 
                marginTop: '1rem', 
                background: '#3498DB', 
                color: 'white',
                borderRadius: '4px',
                padding: '0.8rem 1.5rem',
                boxShadow: '0 2px 5px rgba(0, 0, 0, 0.1)',
                transition: 'all 0.2s ease'
              }}
              onClick={onRetry}
            >
              <Icon name="refresh" />
              Try Again
            </Button>
          )}
        </div>
      </Segment>
    </>
  )
}

export default ErrorDisplay 