import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Container, Header, Checkbox, Dropdown, Button, Segment, Modal, Divider } from 'semantic-ui-react'
import { useAuth0 } from '@auth0/auth0-react'
import { devBoardsApi } from '../api/devBoardsApi'
import { controllersApi } from '../api/controllersApi'

function NewDevBoardPage() {
  const navigate = useNavigate()
  const { getAccessTokenSilently } = useAuth0()
  const [formData, setFormData] = useState({
    name: '',
    wireless: false,
    controller: null,
    url: ''
  })
  const [controllers, setControllers] = useState([])
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)
  const [showNewControllerModal, setShowNewControllerModal] = useState(false)
  const [newController, setNewController] = useState({
    name: '',
    manufacturer: '',
    chipset: '',
    url: ''
  })
  const [creatingController, setCreatingController] = useState(false)
  const [controllerError, setControllerError] = useState(null)

  useEffect(() => {
    const fetchControllers = async () => {
      setLoading(true)
      try {
        const controllersData = await controllersApi.getAll()
        setControllers(controllersData)
        setError(null)
      } catch (error) {
        console.error('Error fetching controllers:', error)
        setError('Failed to load controllers. Please try again.')
      } finally {
        setLoading(false)
      }
    }

    fetchControllers()
  }, [])

  const handleChange = (e) => {
    const { name, value } = e.target
    setFormData(prev => ({
      ...prev,
      [name]: value
    }))
  }

  const handleWirelessChange = (e, { checked }) => {
    setFormData(prev => ({
      ...prev,
      wireless: checked
    }))
  }

  const handleControllerChange = (e, { value }) => {
    if (value === 'new') {
      setShowNewControllerModal(true)
      setFormData(prev => ({
        ...prev,
        controller: null
      }))
    } else {
      setFormData(prev => ({
        ...prev,
        controller: value ? controllers.find(c => c.id === value) : null
      }))
    }
  }

  const handleNewControllerChange = (e) => {
    const { name, value } = e.target
    setNewController(prev => ({
      ...prev,
      [name]: value
    }))
  }

  const handleCreateController = async () => {
    if (!newController.name) {
      setControllerError('Controller name is required')
      return
    }

    setCreatingController(true)
    setControllerError(null)

    try {
      const createdController = await controllersApi.create(newController, getAccessTokenSilently)
      
      setControllers(prev => [createdController, ...prev])
      
      setFormData(prev => ({
        ...prev,
        controller: createdController
      }))
      
      setShowNewControllerModal(false)
      
      setNewController({
        name: '',
        manufacturer: '',
        chipset: '',
        url: ''
      })
    } catch (error) {
      console.error('Error creating controller:', error)
      setControllerError('Failed to create controller. Please try again.')
    } finally {
      setCreatingController(false)
    }
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)

    try {
      await devBoardsApi.create(formData, getAccessTokenSilently)
      navigate('/devboards')
    } catch (error) {
      console.error('Error creating dev board:', error)
      setError('Failed to create dev board. Please try again.')
      setSubmitting(false)
    }
  }

  const controllerOptions = [
    ...controllers.map(controller => ({
      key: controller.id,
      text: `${controller.name} - ${controller.manufacturer || 'Unknown'} ${controller.chipset || ''}`,
      value: controller.id
    })),
    { key: 'new', text: '+ Create New Controller', value: 'new' }
  ]

  return (
    <Container>
      <Header as='h2'>Add New Dev Board</Header>
      
      {error && (
        <Segment inverted color='red'>
          {error}
        </Segment>
      )}
      
      <Form onSubmit={handleSubmit} loading={loading || submitting}>
        <Form.Field required>
          <label htmlFor="name-input">Dev Board Name</label>
          <input
            id="name-input"
            type="text"
            name="name"
            value={formData.name}
            onChange={handleChange}
            placeholder="e.g., nice!nano v2"
            required
          />
        </Form.Field>
        
        <Form.Field>
          <label htmlFor="wireless-input">Wireless</label>
          <Checkbox
            id="wireless-input"
            toggle
            name="wireless"
            checked={formData.wireless}
            onChange={handleWirelessChange}
            label="This dev board supports wireless connectivity"
          />
        </Form.Field>
        
        <Form.Field>
          <label htmlFor="controller-input">Controller</label>
          <Dropdown
            id="controller-input"
            selection
            clearable
            options={controllerOptions}
            placeholder="Select Controller"
            value={formData.controller ? formData.controller.id : null}
            onChange={handleControllerChange}
          />
          <div style={{ fontSize: '0.9em', color: '#666', marginTop: '0.5em' }}>
            Select the microcontroller used in this dev board or create a new one.
          </div>
        </Form.Field>
        
        <Form.Field>
          <label htmlFor="url-input">URL (Optional)</label>
          <input
            id="url-input"
            type="url"
            name="url"
            value={formData.url}
            onChange={handleChange}
            placeholder="e.g., https://nicekeyboards.com/nice-nano"
          />
          <div style={{ fontSize: '0.9em', color: '#666', marginTop: '0.5em' }}>
            A link to the product page or documentation for this dev board.
          </div>
        </Form.Field>
        
        <Button.Group>
          <Button type="button" onClick={() => navigate('/devboards')}>
            Cancel
          </Button>
          <Button.Or />
          <Button 
            type="submit" 
            primary 
            disabled={!formData.name} 
            loading={submitting}
          >
            Create Dev Board
          </Button>
        </Button.Group>
      </Form>

      <Modal
        open={showNewControllerModal}
        onClose={() => setShowNewControllerModal(false)}
        size="small"
      >
        <Modal.Header>Create New Controller</Modal.Header>
        <Modal.Content>
          {controllerError && (
            <Segment inverted color='red' style={{ marginBottom: '1em' }}>
              {controllerError}
            </Segment>
          )}
          
          <Form loading={creatingController}>
            <Form.Field required>
              <label htmlFor="controller-name-input">Controller Name</label>
              <input
                id="controller-name-input"
                type="text"
                name="name"
                value={newController.name}
                onChange={handleNewControllerChange}
                placeholder="e.g., Pro Micro"
                required
              />
            </Form.Field>
            
            <Form.Field>
              <label htmlFor="manufacturer-input">Manufacturer</label>
              <input
                id="manufacturer-input"
                type="text"
                name="manufacturer"
                value={newController.manufacturer}
                onChange={handleNewControllerChange}
                placeholder="e.g., SparkFun"
              />
            </Form.Field>
            
            <Form.Field>
              <label htmlFor="chipset-input">Chipset</label>
              <input
                id="chipset-input"
                type="text"
                name="chipset"
                value={newController.chipset}
                onChange={handleNewControllerChange}
                placeholder="e.g., ATmega32U4"
              />
            </Form.Field>
            
            <Form.Field>
              <label htmlFor="controller-url-input">URL (Optional)</label>
              <input
                id="controller-url-input"
                type="url"
                name="url"
                value={newController.url}
                onChange={handleNewControllerChange}
                placeholder="e.g., https://www.sparkfun.com/products/12640"
              />
            </Form.Field>
          </Form>
        </Modal.Content>
        <Modal.Actions>
          <Button onClick={() => setShowNewControllerModal(false)}>
            Cancel
          </Button>
          <Button 
            primary 
            onClick={handleCreateController}
            disabled={!newController.name}
            loading={creatingController}
          >
            Create Controller
          </Button>
        </Modal.Actions>
      </Modal>
    </Container>
  )
}

export default NewDevBoardPage 