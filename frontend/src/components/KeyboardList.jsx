import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Header, Table, Loader, Icon } from 'semantic-ui-react'
import { useAuth0 } from '@auth0/auth0-react'
import ErrorDisplay from './ErrorDisplay'

function KeyboardList() {
  const navigate = useNavigate()
  const [keyboards, setKeyboards] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const { isAuthenticated } = useAuth0()

  useEffect(() => {
    fetchKeyboards()
  }, [])

  const fetchKeyboards = async () => {
    try {
      const response = await fetch('http://localhost:8080/api/keyboards')
      if (!response.ok) {
        throw new Error('Failed to fetch keyboards')
      }
      const data = await response.json()
      setKeyboards(data)
      setError(null)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const handleAddKeyboard = () => {
    navigate('/keyboards/new')
  }

  const handleKeyboardClick = (id) => {
    navigate(`/keyboards/${id}`)
  }

  // Helper function to render Yes/No values with icons
  const renderYesNo = (value) => {
    if (value) {
      return <span style={{ color: 'green' }}><Icon name="check" /> Yes</span>
    }
    return <span style={{ color: 'red' }}><Icon name="close" /> No</span>
  }

  if (loading) {
    return (
      <div className="container" style={{ margin: '0 auto', maxWidth: '1200px', padding: '0 1rem' }}>
        <Loader active>Loading keyboards...</Loader>
      </div>
    )
  }

  return (
    <div className="container" style={{ margin: '0 auto', maxWidth: '1200px', padding: '0 1rem' }}>
      {!error && (
        <div style={{ 
          marginBottom: '2rem',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center'
        }}>
          <Header as='h2' content="Keyboard List" />
          {isAuthenticated && (
            <button 
              className="ui primary button"
              onClick={handleAddKeyboard}
            >
              Add Keyboard
            </button>
          )}
        </div>
      )}

      {error && (
        <ErrorDisplay 
          errorMessage={error} 
          onRetry={fetchKeyboards}
        />
      )}

      {!error && (
        <>
          <div className="table-container" style={{ 
            overflowX: 'auto', 
            width: '100%',
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            borderRadius: '4px',
            WebkitOverflowScrolling: 'touch', // For smoother scrolling on iOS
          }}>
            <Table celled striped selectable unstackable style={{ minWidth: '650px' }}>
              <Table.Header>
                <Table.Row>
                  <Table.HeaderCell>Name</Table.HeaderCell>
                  <Table.HeaderCell>Split</Table.HeaderCell>
                  <Table.HeaderCell>Hotswap</Table.HeaderCell>
                  <Table.HeaderCell>Unibody</Table.HeaderCell>
                  <Table.HeaderCell>Splay</Table.HeaderCell>
                  <Table.HeaderCell>Row Stagger</Table.HeaderCell>
                  <Table.HeaderCell>Column Stagger</Table.HeaderCell>
                  <Table.HeaderCell>Board/Controller</Table.HeaderCell>
                  {/* Add more columns here as new properties are added */}
                </Table.Row>
              </Table.Header>

              <Table.Body>
                {keyboards.map((keyboard) => (
                  <Table.Row 
                    key={keyboard.id} 
                    onClick={() => handleKeyboardClick(keyboard.id)}
                    style={{ cursor: 'pointer' }}
                    className="keyboard-row"
                  >
                    <Table.Cell>
                      <strong>{keyboard.name}</strong>
                    </Table.Cell>
                    <Table.Cell>{renderYesNo(keyboard.split)}</Table.Cell>
                    <Table.Cell>{renderYesNo(keyboard.hotswap)}</Table.Cell>
                    <Table.Cell>{renderYesNo(keyboard.unibody)}</Table.Cell>
                    <Table.Cell>{renderYesNo(keyboard.splay)}</Table.Cell>
                    <Table.Cell>{renderYesNo(keyboard.rowStagger)}</Table.Cell>
                    <Table.Cell>{renderYesNo(keyboard.columnStagger)}</Table.Cell>
                    <Table.Cell>
                      {keyboard.devBoard ? 
                        <span style={{ color: 'blue' }}>
                          DevBoard: {keyboard.devBoard.name}
                          {keyboard.devBoard.controller && 
                            <span style={{ fontStyle: 'italic', color: 'purple' }}> ({keyboard.devBoard.controller.name})</span>
                          }
                        </span> : 
                        keyboard.controller ? 
                        <span style={{ color: 'purple' }}>Controller: {keyboard.controller.name}</span> : 
                        <span style={{ color: 'gray', fontStyle: 'italic' }}>None</span>}
                    </Table.Cell>
                    {/* Add more cells here as new properties are added */}
                  </Table.Row>
                ))}
              </Table.Body>
            </Table>
          </div>

          {keyboards.length === 0 && (
            <div style={{ textAlign: 'center', marginTop: '2rem', color: '#666' }}>
              No keyboards found.
            </div>
          )}

          <style jsx>{`
            .keyboard-row:hover {
              background-color: #f9fafb !important;
            }
            
            .table-container::-webkit-scrollbar {
              height: 8px;
            }
            
            .table-container::-webkit-scrollbar-thumb {
              background-color: rgba(0, 0, 0, 0.2);
              border-radius: 4px;
            }
            
            .table-container::-webkit-scrollbar-track {
              background-color: rgba(0, 0, 0, 0.05);
            }
            
            @media (max-width: 767px) {
              .table-container {
                margin-bottom: 1rem;
              }
            }
          `}</style>
        </>
      )}
    </div>
  )
}

export default KeyboardList 