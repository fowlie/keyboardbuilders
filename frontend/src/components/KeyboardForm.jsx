import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Container, Header } from 'semantic-ui-react'

function KeyboardForm({ keyboard, onSubmit, mode = 'new' }) {
  const navigate = useNavigate()
  const [formData, setFormData] = useState(keyboard || {
    name: '',
    split: false
  })

  const handleChange = (e) => {
    const { name, value } = e.target
    setFormData(prev => ({
      ...prev,
      [name]: value
    }))
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    await onSubmit(formData)
    navigate('/keyboards')
  }

  return (
    <Container>
      <Header as='h2'>{mode === 'new' ? 'Add New Keyboard' : 'Edit Keyboard'}</Header>
      <Form onSubmit={handleSubmit}>
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

        <button
          className="ui primary button"
          type="submit"
        >
          {mode === 'new' ? 'Create Keyboard' : 'Update Keyboard'}
        </button>
      </Form>
    </Container>
  )
}

export default KeyboardForm