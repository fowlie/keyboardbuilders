import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Container, Header, Dropdown, Checkbox, Segment, Button } from 'semantic-ui-react'
import { useAuth0 } from '@auth0/auth0-react'
import { devBoardsApi } from '../api/devBoardsApi'
import { controllersApi } from '../api/controllersApi'

function KeyboardForm({ keyboard, onSubmit, mode = 'new' }) {
  const navigate = useNavigate()
  const { getAccessTokenSilently } = useAuth0()
  const [formData, setFormData] = useState(keyboard || {
    name: '',
    split: false,
    hotswap: false,
    unibody: false,
    splay: false,
    rowStagger: false,
    columnStagger: false,
    url: '',
    devBoard: null,
    controller: null
  })
  const [devBoards, setDevBoards] = useState([])
  const [controllers, setControllers] = useState([])
  const [loading, setLoading] = useState(false)
  const [newDevBoard, setNewDevBoard] = useState({
    name: '',
    wireless: false,
    controller: null
  })
  const [showNewDevBoardForm, setShowNewDevBoardForm] = useState(false)

  useEffect(() => {
    const fetchData = async () => {
      setLoading(true)
      try {
        const [devBoardsData, controllersData] = await Promise.all([
          devBoardsApi.getAll(),
          controllersApi.getAll()
        ])
        setDevBoards(devBoardsData)
        setControllers(controllersData)
      } catch (error) {
        console.error('Error fetching data:', error)
      } finally {
        setLoading(false)
      }
    }

    fetchData()
  }, [])

  const handleChange = (e) => {
    const { name, value } = e.target
    setFormData(prev => ({
      ...prev,
      [name]: value
    }))
  }

  const handleDevBoardChange = (e, { value }) => {
    if (value === 'new') {
      setShowNewDevBoardForm(true)
      setFormData(prev => ({
        ...prev,
        devBoard: null,
        controller: null
      }))
    } else {
      setShowNewDevBoardForm(false)
      // If selecting a dev board, clear controller
      setFormData(prev => ({
        ...prev,
        devBoard: value ? devBoards.find(b => b.id === value) : null,
        controller: null
      }))
    }
  }

  const handleControllerChange = (e, { value }) => {
    // If selecting a controller, clear dev board
    setFormData(prev => ({
      ...prev,
      controller: value ? controllers.find(c => c.id === value) : null,
      devBoard: null
    }))
    
    // Also update the controller in the new dev board form
    if (showNewDevBoardForm) {
      setNewDevBoard(prev => ({
        ...prev,
        controller: value ? controllers.find(c => c.id === value) : null
      }))
    }
  }

  const handleNewDevBoardChange = (e) => {
    const { name, value } = e.target
    setNewDevBoard(prev => ({
      ...prev,
      [name]: value
    }))
  }

  const handleNewDevBoardWirelessChange = (e, { checked }) => {
    setNewDevBoard(prev => ({
      ...prev,
      wireless: checked
    }))
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    
    // If creating a new dev board
    if (showNewDevBoardForm && newDevBoard.name && newDevBoard.controller) {
      try {
        // Create the new dev board
        const createdDevBoard = await devBoardsApi.create(newDevBoard, getAccessTokenSilently)
        
        // Update the form data with the newly created dev board
        const updatedFormData = {
          ...formData,
          devBoard: createdDevBoard,
          controller: null
        }
        
        // Submit the updated form data
        await onSubmit(updatedFormData)
      } catch (error) {
        console.error('Error creating dev board:', error)
      }
    } else {
      // Normal form submission
      await onSubmit(formData)
    }
    
    navigate('/keyboards')
  }

  const devBoardOptions = [
    ...devBoards.map(board => ({
      key: board.id,
      text: board.name + (board.controller ? ` (${board.controller.name})` : ''),
      value: board.id
    })),
    { key: 'new', text: '+ Add New Dev Board', value: 'new' }
  ]

  const controllerOptions = controllers.map(controller => ({
    key: controller.id,
    text: `${controller.name} - ${controller.manufacturer || 'Unknown'} ${controller.chipset || ''}`,
    value: controller.id
  }))

  return (
    <Container>
      <Header as='h2'>{mode === 'new' ? 'Add New Keyboard' : 'Edit Keyboard'}</Header>
      <Form onSubmit={handleSubmit} loading={loading}>
        <Form.Field>
          <label>Name</label>
          <input
            className="ui input"
            type="text"
            name="name"
            value={formData.name}
            onChange={handleChange}
            required
            style={{ width: '100%', padding: '0.67857143em 1em', borderRadius: '0.28571429rem', border: '1px solid rgba(34,36,38,.15)' }}
          />
        </Form.Field>

        <Form.Field>
          <label>Split</label>
          <div style={{ fontSize: '0.9em', color: '#666', marginBottom: '0.5em' }}>
            Hint: A split keyboard usually means two physical parts, but it could also be a split keyboard even if it's just one physical keyboard (like the Reviung).
          </div>
          <input
            type="checkbox"
            name="split"
            checked={formData.split || false}
            onChange={(e) => handleChange({
              target: {
                name: 'split',
                value: e.target.checked
              }
            })}
          />
        </Form.Field>

        <Form.Field>
          <label>Hotswap</label>
          <input
            type="checkbox"
            name="hotswap"
            checked={formData.hotswap || false}
            onChange={(e) => handleChange({
              target: {
                name: 'hotswap',
                value: e.target.checked
              }
            })}
          />
        </Form.Field>

        <Form.Field>
          <label>Unibody</label>
          <input
            type="checkbox"
            name="unibody"
            checked={formData.unibody || false}
            onChange={(e) => handleChange({
              target: {
                name: 'unibody',
                value: e.target.checked
              }
            })}
          />
        </Form.Field>

        <Form.Field>
          <label>Splay</label>
          <input
            type="checkbox"
            name="splay"
            checked={formData.splay || false}
            onChange={(e) => handleChange({
              target: {
                name: 'splay',
                value: e.target.checked
              }
            })}
          />
        </Form.Field>

        <Form.Field>
          <label>Row Stagger</label>
          <input
            type="checkbox"
            name="rowStagger"
            checked={formData.rowStagger || false}
            onChange={(e) => handleChange({
              target: {
                name: 'rowStagger',
                value: e.target.checked
              }
            })}
          />
        </Form.Field>

        <Form.Field>
          <label>Column Stagger</label>
          <input
            type="checkbox"
            name="columnStagger"
            checked={formData.columnStagger || false}
            onChange={(e) => handleChange({
              target: {
                name: 'columnStagger',
                value: e.target.checked
              }
            })}
          />
        </Form.Field>

        <Form.Field>
          <label>URL</label>
          <input
            className="ui input"
            type="url"
            name="url"
            value={formData.url || ''}
            onChange={handleChange}
            placeholder="https://example.com/keyboard"
            style={{ width: '100%', padding: '0.67857143em 1em', borderRadius: '0.28571429rem', border: '1px solid rgba(34,36,38,.15)' }}
          />
        </Form.Field>

        {!showNewDevBoardForm ? (
          <>
            <Form.Field>
              <label>Dev Board</label>
              <div style={{ fontSize: '0.9em', color: '#666', marginBottom: '0.5em' }}>
                Note: You can only select either a Dev Board or a Controller (directly), not both.
              </div>
              <Dropdown
                selection
                clearable
                options={devBoardOptions}
                placeholder="Select Dev Board"
                value={formData.devBoard ? formData.devBoard.id : null}
                onChange={handleDevBoardChange}
                disabled={formData.controller !== null}
              />
            </Form.Field>

            <Form.Field>
              <label>Controller (Direct)</label>
              <div style={{ fontSize: '0.9em', color: '#666', marginBottom: '0.5em' }}>
                Note: Select this only if the controller is connected directly to the keyboard, not through a dev board.
              </div>
              <Dropdown
                selection
                clearable
                options={controllerOptions}
                placeholder="Select Controller"
                value={formData.controller ? formData.controller.id : null}
                onChange={handleControllerChange}
                disabled={formData.devBoard !== null || showNewDevBoardForm}
              />
            </Form.Field>
          </>
        ) : (
          // New Dev Board Form
          <Segment>
            <Header as='h3'>Add New Dev Board</Header>
            <Form.Field>
              <label>Dev Board Name</label>
              <input
                className="ui input"
                type="text"
                name="name"
                value={newDevBoard.name}
                onChange={handleNewDevBoardChange}
                required
                placeholder="e.g., nice!nano v2"
                style={{ width: '100%', padding: '0.67857143em 1em', borderRadius: '0.28571429rem', border: '1px solid rgba(34,36,38,.15)' }}
              />
            </Form.Field>

            <Form.Field>
              <label>Wireless</label>
              <Checkbox
                toggle
                name="wireless"
                checked={newDevBoard.wireless}
                onChange={handleNewDevBoardWirelessChange}
              />
            </Form.Field>

            <Form.Field>
              <label>Controller Type</label>
              <div style={{ fontSize: '0.9em', color: '#666', marginBottom: '0.5em' }}>
                Select the controller used in this dev board.
              </div>
              <Dropdown
                selection
                options={controllerOptions}
                placeholder="Select Controller"
                value={newDevBoard.controller ? newDevBoard.controller.id : null}
                onChange={handleControllerChange}
                required
              />
            </Form.Field>
            
            <Button type="button" onClick={() => setShowNewDevBoardForm(false)}>
              Cancel
            </Button>
          </Segment>
        )}

        <button
          className="ui primary button"
          type="submit"
          style={{ marginTop: '1rem' }}
        >
          {mode === 'new' ? 'Create Keyboard' : 'Update Keyboard'}
        </button>
      </Form>
    </Container>
  )
}

export default KeyboardForm