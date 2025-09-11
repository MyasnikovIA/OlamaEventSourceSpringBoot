let eventSource = null;
let currentAssistantMessage = null;
let isGenerating = false;
let attachedFiles = []; // Массив для хранения прикрепленных файлов

// Функции для работы с чатом
function addMessage(content, isUser = false) {
    const chatMessages = document.getElementById('chatMessages');
    const messageDiv = document.createElement('div');
    messageDiv.className = isUser ? 'message user-message' : 'message assistant-message';

    if (!isUser) {
        messageDiv.classList.add('streaming');
        const contentDiv = document.createElement('div');
        contentDiv.className = 'streaming-content';

        // Создаем pre тег для сырого текста во время стриминга
        const preElement = document.createElement('pre');
        preElement.textContent = content;
        contentDiv.appendChild(preElement);

        messageDiv.appendChild(contentDiv);

        const cursor = document.createElement('span');
        cursor.className = 'streaming-cursor';
        cursor.textContent = '|';
        messageDiv.appendChild(cursor);
        currentAssistantMessage = messageDiv;
    } else {
        // Для пользовательских сообщений используем pre с экранированием
        messageDiv.innerHTML = "<pre>" + escapeHtml(content) + "</pre>";
    }

    chatMessages.appendChild(messageDiv);
    chatMessages.scrollTop = chatMessages.scrollHeight;

    // Обновляем состояние кнопки очистки
    updateClearChatButton();

    return messageDiv;
}


// Функции для работы с файлами
function handleFileSelect(files) {
    for (let i = 0; i < files.length; i++) {
        const file = files[i];
        if (attachedFiles.some(f => f.name === file.name && f.size === file.size)) {
            continue; // Пропускаем дубликаты
        }

        attachedFiles.push(file);
        displayAttachedFile(file);
    }
}

function displayAttachedFile(file) {
    const attachedFilesContainer = document.getElementById('attachedFiles');
    const fileElement = document.createElement('div');
    fileElement.className = 'attached-file';
    fileElement.dataset.filename = file.name;

    const fileName = document.createElement('span');
    fileName.className = 'file-name';
    fileName.textContent = file.name;

    const removeBtn = document.createElement('button');
    removeBtn.className = 'remove-file';
    removeBtn.textContent = '×';
    removeBtn.onclick = () => removeAttachedFile(file.name);

    fileElement.appendChild(fileName);
    fileElement.appendChild(removeBtn);
    attachedFilesContainer.appendChild(fileElement);
}

function removeAttachedFile(filename) {
    attachedFiles = attachedFiles.filter(file => file.name !== filename);
    const fileElement = document.querySelector(`.attached-file[data-filename="${filename}"]`);
    if (fileElement) {
        fileElement.remove();
    }
}

function clearAttachedFiles() {
    attachedFiles = [];
    document.getElementById('attachedFiles').innerHTML = '';
}

// Функции для обработки файлов
function readTextFile(file, callback) {
    const reader = new FileReader();
    reader.onload = (e) => callback(null, e.target.result);
    reader.onerror = (e) => callback(e);
    reader.readAsText(file);
}

function readImageAsBase64(file, callback) {
    const reader = new FileReader();
    reader.onload = (e) => callback(null, e.target.result.split(',')[1]);
    reader.onerror = (e) => callback(e);
    reader.readAsDataURL(file);
}

// Функция для обработки прикрепленных файлов
function processAttachedFiles(callback) {
    const textFilesContent = [];
    const imagesBase64 = [];

    let filesProcessed = 0;
    const totalFiles = attachedFiles.length;

    if (totalFiles === 0) {
        callback(null, {
            textContent: textFilesContent.join('\r\n'),
            images: imagesBase64
        });
        return;
    }

    attachedFiles.forEach(file => {
        if (file.type.startsWith('text/') ||
            file.type === 'application/json' ||
            file.name.endsWith('.txt') ||
            file.name.endsWith('.md')) {

            readTextFile(file, (error, content) => {
                if (error) {
                    console.error('Ошибка чтения текстового файла:', error);
                } else {
                    textFilesContent.push(content);
                }

                filesProcessed++;
                if (filesProcessed === totalFiles) {
                    callback(null, {
                        textContent: textFilesContent.join('\r\n'),
                        images: imagesBase64
                    });
                }
            });

        } else if (file.type.startsWith('image/')) {

            readImageAsBase64(file, (error, base64) => {
                if (error) {
                    console.error('Ошибка чтения изображения:', error);
                } else {
                    imagesBase64.push(base64);
                }

                filesProcessed++;
                if (filesProcessed === totalFiles) {
                    callback(null, {
                        textContent: textFilesContent.join('\r\n'),
                        images: imagesBase64
                    });
                }
            });

        } else {
            // Пропускаем файлы других типов, но учитываем в счетчике
            filesProcessed++;
            if (filesProcessed === totalFiles) {
                callback(null, {
                    textContent: textFilesContent.join('\r\n'),
                    images: imagesBase64
                });
            }
        }
    });
}

// Функция для обработки прикрепленных файлов для команды doc:
function processAttachedFilesForDoc(callback) {
    const documents = [];
    let filesProcessed = 0;
    const totalFiles = attachedFiles.filter(file =>
        file.type.startsWith('text/') ||
        file.type === 'application/json' ||
        file.name.endsWith('.txt') ||
        file.name.endsWith('.md')
    ).length;

    if (totalFiles === 0) {
        callback(null, documents);
        return;
    }
    attachedFiles.forEach(file => {
        if (file.type.startsWith('text/') ||
            file.type === 'application/json' ||
            file.name.endsWith('.txt') ||
            file.name.endsWith('.md')) {

            readTextFile(file, (error, content) => {
                if (error) {
                    console.error('Ошибка чтения текстового файла:', error);
                } else {
                    documents.push(content);
                }

                filesProcessed++;
                if (filesProcessed === totalFiles) {
                    callback(null, documents);
                }
            });
        } else {
            // Пропускаем нетекстовые файлы, но учитываем в счетчике
            filesProcessed++;
            if (filesProcessed === totalFiles) {
                callback(null, documents);
            }
        }
    });
}
// Функция для отправки сообщения в чат
// Функция для отправки сообщения в чат
function sendMessage() {
    const messageInput = document.getElementById('messageInput');
    let userInput = messageInput.value.trim();

    // Проверяем, начинается ли сообщение с "doc:"
    const isDocCommand = userInput.toLowerCase().startsWith('doc:');

    // Если это команда doc:, убираем ключевое слово из сообщения
    if (isDocCommand) {
        userInput = userInput.substring(4).trim();
    }

    if (!userInput && attachedFiles.length === 0) {
        console.error('Пустое сообщение и нет прикрепленных файлов');
        return;
    }

    // Добавляем сообщение пользователя в чат
    addMessage(isDocCommand ? "doc: " + userInput : userInput, true);

    const chatId = getOrCreateChatId();

    // Обрабатываем прикрепленные файлы
    if (attachedFiles.length > 0) {
        if (isDocCommand) {
            // Для команды doc: обрабатываем файлы отдельно
            processAttachedFilesForDoc((error, docFiles) => {
                if (error) {
                    console.error('Ошибка обработки файлов:', error);
                    return;
                }

                processAttachedFiles((error, filesData) => {
                    if (error) {
                        console.error('Ошибка обработки файлов:', error);
                        return;
                    }

                    sendMessageRequest(chatId, userInput, isDocCommand, filesData, docFiles);
                });
            });
        } else {
            // Обычная обработка
            processAttachedFiles((error, filesData) => {
                if (error) {
                    console.error('Ошибка обработки файлов:', error);
                    return;
                }

                sendMessageRequest(chatId, userInput, isDocCommand, filesData, []);
            });
        }
    } else {
        sendMessageRequest(chatId, userInput, isDocCommand, {textContent: '', images: []}, []);
    }
}


// Вспомогательная функция для отправки запроса
function sendMessageRequest(chatId, userInput, isDocCommand, filesData, docFiles) {
    const chatModel = document.getElementById('chatModel').value || "llama3.2-vision:latest";
    const embeddingModel = document.getElementById('embeddingModel').value;
    const systemPrompt = document.getElementById('systemPrompt').value || "";

    let content = userInput + (filesData.textContent ? '\r\n' + filesData.textContent : '');
    if (isDocCommand && docFiles.length > 0) {
        content = 'doc:' + userInput;
    }

    const jsonBody = {
        "model": chatModel,
        "system_prompt":systemPrompt,
        "embeddingModel": embeddingModel, // Всегда отправляем embeddingModel для RAG
        "messages": [
            {
                "role": "user",
                "content": content,
                "images": filesData.images.length > 0 ? filesData.images : undefined
            }
        ],
        "stream": true
    };

    if (isDocCommand && docFiles.length > 0) {
        jsonBody.doc = docFiles;
    }

    if (filesData.images.length === 0) {
        delete jsonBody.messages[0].images;
    }

    console.log('Отправка RAG запроса на /chat/' + chatId, {
        ...jsonBody,
        images: filesData.images.length > 0 ? '[' + filesData.images.length + ' images]' : 'none',
        doc: isDocCommand && docFiles.length > 0 ? '[' + docFiles.length + ' documents]' : 'none'
    });

    completeAssistantMessage();

    fetch('/chat/' + chatId, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(jsonBody)
    })
        .then(response => {
            if (!response.ok) {
                throw new Error('Ошибка сети: ' + response.status);
            }
            document.getElementById('messageInput').value = '';
            clearAttachedFiles();
            isGenerating = true;
            updateUIState();
        })
        .catch(error => {
            console.error('Ошибка при отправке RAG запроса:', error);
            completeAssistantMessage();
        });
}

// Аналогично нужно переписать функцию generateText
function generateText() {
    const messageInput = document.getElementById('messageInput');
    let userInput = messageInput.value.trim();

    // Проверяем, начинается ли сообщение с "doc:"
    const isDocCommand = userInput.toLowerCase().startsWith('doc:');

    // Если это команда doc:, убираем ключевое слово из сообщения
    if (isDocCommand) {
        userInput = userInput.substring(4).trim();
    }

    if (!userInput && attachedFiles.length === 0) {
        console.error('Пустой промпт и нет прикрепленных файлов');
        return;
    }

    // Добавляем промпт пользователя в чат
    addMessage("⚡ Generate: " + (isDocCommand ? "doc: " + userInput : userInput), true);

    const chatId = getOrCreateChatId();

    // Обрабатываем прикрепленные файлы
    if (attachedFiles.length > 0) {
        if (isDocCommand) {
            processAttachedFilesForDoc((error, docFiles) => {
                if (error) {
                    console.error('Ошибка обработки файлов:', error);
                    return;
                }

                processAttachedFiles((error, filesData) => {
                    if (error) {
                        console.error('Ошибка обработки файлов:', error);
                        return;
                    }

                    generateTextRequest(chatId, userInput, isDocCommand, filesData, docFiles);
                });
            });
        } else {
            processAttachedFiles((error, filesData) => {
                if (error) {
                    console.error('Ошибка обработки файлов:', error);
                    return;
                }

                generateTextRequest(chatId, userInput, isDocCommand, filesData, []);
            });
        }
    } else {
        generateTextRequest(chatId, userInput, isDocCommand, {textContent: '', images: []}, []);
    }
}

// Вспомогательная функция для отправки запроса генерации
function generateTextRequest(chatId, userInput, isDocCommand, filesData, docFiles) {
    const chatModel = document.getElementById('chatModel').value || "llama3.2-vision:latest";
    const embeddingModel = document.getElementById('embeddingModel').value || "";
    const systemPrompt = document.getElementById('systemPrompt').value|| "";

    let content = userInput + (filesData.textContent ? '\r\n' + filesData.textContent : '');
    if (isDocCommand && docFiles.length > 0) {
        content = 'doc:' + userInput;
    }

    const jsonBody = {
        "model": chatModel,
        "embeddingModel": embeddingModel, // Всегда отправляем embeddingModel для RAG
        "prompt": content,
        "system_prompt":systemPrompt,
        "images": filesData.images.length > 0 ? filesData.images : undefined,
        "stream": true
    };

    if (isDocCommand && docFiles.length > 0) {
        jsonBody.doc = docFiles;
    }

    if (filesData.images.length === 0) {
        delete jsonBody.images;
    }

    console.log('Отправка RAG запроса на /generate/' + chatId, {
        ...jsonBody,
        images: filesData.images.length > 0 ? '[' + filesData.images.length + ' images]' : 'none',
        doc: isDocCommand && docFiles.length > 0 ? '[' + docFiles.length + ' documents]' : 'none'
    });

    completeAssistantMessage();

    fetch('/generate/' + chatId, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(jsonBody)
    })
        .then(response => {
            if (!response.ok) {
                throw new Error('Ошибка сети: ' + response.status);
            }
            document.getElementById('messageInput').value = '';
            clearAttachedFiles();
            isGenerating = true;
            updateUIState();
        })
        .catch(error => {
            console.error('Ошибка при отправке RAG запроса генерации:', error);
            completeAssistantMessage();
        });
}

// Функции для работы с документами RAG
function initDocumentUpload() {
    const documentDropZone = document.getElementById('documentDropZone');
    const documentFileInput = document.getElementById('documentFileInput');
    const documentContent = document.getElementById('documentContent');
    const addDocumentBtn = document.getElementById('addDocumentBtn');
    const documentStatus = document.getElementById('documentStatus');

    // Клик по drop zone открывает файловый диалог
    documentDropZone.addEventListener('click', () => {
        documentFileInput.click();
    });

    // Обработка выбора файлов
    documentFileInput.addEventListener('change', (e) => {
        handleDocumentFileSelect(e.target.files[0]);
        documentFileInput.value = '';
    });

    // Drag and drop для документов
    documentDropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        documentDropZone.classList.add('dragover');
    });

    documentDropZone.addEventListener('dragleave', () => {
        documentDropZone.classList.remove('dragover');
    });

    documentDropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        documentDropZone.classList.remove('dragover');

        if (e.dataTransfer.files.length > 0) {
            handleDocumentFileSelect(e.dataTransfer.files[0]);
        }
    });

    // Обработка кнопки добавления документа
    addDocumentBtn.addEventListener('click', addDocumentToRAG);
}

function handleDocumentFileSelect(file) {
    if (!file) return;

    // Проверяем тип файла
    const allowedTypes = ['text/plain', 'text/markdown', 'application/json'];
    const allowedExtensions = ['.txt', '.md', '.json'];

    const fileExtension = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();

    if (!allowedTypes.includes(file.type) && !allowedExtensions.includes(fileExtension)) {
        showDocumentStatus('Ошибка: разрешены только текстовые файлы (.txt, .md, .json)', 'error');
        return;
    }

    const reader = new FileReader();
    reader.onload = (e) => {
        document.getElementById('documentContent').value = e.target.result;
        showDocumentStatus(`Файл "${file.name}" загружен`, 'success');
    };
    reader.onerror = (e) => {
        showDocumentStatus('Ошибка чтения файла', 'error');
    };
    reader.readAsText(file);
}


async function addDocumentToRAG() {
    const content = document.getElementById('documentContent').value.trim();
    const documentStatus = document.getElementById('documentStatus');

    if (!content) {
        showDocumentStatus('Ошибка: документ не может быть пустым', 'error');
        return;
    }

    showDocumentStatus('Добавление документа...', 'loading');

    try {
        const chatId = getOrCreateChatId();
        const response = await fetch('/api/documents/'+chatId, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            body: new URLSearchParams({
                content: content,
                metadata: JSON.stringify({
                    source: 'web_upload',
                    timestamp: new Date().toISOString()
                })
            })
        });
        const responseData = await response.json();
        if (!response.ok) {
            // Проверяем, является ли ошибка дубликатом
            if (responseData.code === 'DUPLICATE_DOCUMENT') {
                throw new Error('Этот документ уже существует в базе данных');
            }
            throw new Error(responseData.error || 'Ошибка при добавлении документа');
        }

        showDocumentStatus(`Документ успешно добавлен (ID: ${responseData.id})`, 'success');

        // Очищаем поле через 2 секунды
        setTimeout(() => {
            document.getElementById('documentContent').value = '';
            documentStatus.textContent = '';
            documentStatus.className = 'document-status';
        }, 2000);

    } catch (error) {
        console.error('Ошибка при добавлении документа:', error);
        showDocumentStatus(`Ошибка: ${error.message}`, 'error');
    }
}

function showDocumentStatus(message, type) {
    const statusElement = document.getElementById('documentStatus');
    statusElement.textContent = message;
    statusElement.className = `document-status ${type}`;

    // Автоматическое скрытие успешных сообщений через 5 секунд
    if (type === 'success') {
        setTimeout(() => {
            statusElement.textContent = '';
            statusElement.className = 'document-status';
        }, 5000);
    }
}

function updateAssistantMessage(content) {
    if (currentAssistantMessage) {
        const contentDiv = currentAssistantMessage.querySelector('.streaming-content');
        if (contentDiv) {
            const preElement = contentDiv.querySelector('pre');
            if (preElement) {
                preElement.textContent = content;
            } else {
                // Если pre тега нет, создаем его
                const newPreElement = document.createElement('pre');
                newPreElement.textContent = content;
                contentDiv.innerHTML = '';
                contentDiv.appendChild(newPreElement);
            }
        }
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }
}

function completeAssistantMessage() {
    if (currentAssistantMessage) {
        currentAssistantMessage.classList.remove('streaming');
        const cursor = currentAssistantMessage.querySelector('.streaming-cursor');
        if (cursor) {
            cursor.remove();
        }

        // Форматируем содержимое сообщения
        const contentDiv = currentAssistantMessage.querySelector('.streaming-content');
        if (contentDiv) {
            // Получаем сырой текст из pre тега
            const preElement = contentDiv.querySelector('pre');
            let rawContent = '';

            if (preElement) {
                rawContent = preElement.textContent;
                // Удаляем pre тег
                contentDiv.removeChild(preElement);
            } else {
                rawContent = contentDiv.textContent;
            }

            // Применяем форматирование
            const formattedContent = formatAssistantMessage(rawContent);
            contentDiv.innerHTML = formattedContent;
        }

        currentAssistantMessage = null;

        // Обновляем состояние кнопки очистки
        updateClearChatButton();
    }
}

function clearChat() {
    document.getElementById('chatMessages').innerHTML = '';
    currentAssistantMessage = null;
    clearChat();
    clearAttachedFiles(); // Также очищаем прикрепленные файлы
}

// Функции SSE
function connectSSE() {
    updateConnectionStatus('connecting', 'Подключение к SSE...');

    let chatId = getOrCreateChatId();
    if (eventSource) {
        eventSource.close();
    }

    try {
        eventSource = new EventSource('/events?chat_id=' + chatId);

        eventSource.onopen = function () {
            console.log('✅ Соединение установлено');
            updateConnectionStatus('connected', 'Подключено к SSE');
        };

        eventSource.onerror = function (error) {
            console.error('❌ Ошибка SSE: ' + JSON.stringify(error));
            updateConnectionStatus('disconnected', 'Отключено от SSE');
            disconnectSSE();
        };

        eventSource.onmessage = function (event) {
            // console.log('📨 Сообщение: ' + event.data);
        };

        // Обработка событий от Ollama
        eventSource.addEventListener('message', function (event) {
            const data = JSON.parse(event.data);
            const fragment = data['content'];
            if (fragment !== undefined && fragment !== null) {
                // Для каждого нового ответа создаем новый блок сообщения
                if (!currentAssistantMessage) {
                    addMessage(fragment, false);
                } else {
                    const currentContent = currentAssistantMessage.querySelector('.streaming-content').textContent;
                    updateAssistantMessage(currentContent + fragment);
                }
            }
        });

        eventSource.addEventListener('complete', function (event) {
            const data = JSON.parse(event.data);
            if (data && data.final_content) {
                completeAssistantMessage();
            } else {
                completeAssistantMessage();
            }
            isGenerating = false;
            updateUIState();
        });

        // Обработка начала нового ответа
        eventSource.addEventListener('start', function () {
            // Завершаем предыдущее сообщение (если было)
            completeAssistantMessage();
            // Создаем новое пустое сообщение для нового ответа
            addMessage('', false);
            isGenerating = true;
            updateUIState();
        });

        // Обработка ошибок
        eventSource.addEventListener('error', function (event) {
            const data = JSON.parse(event.data);
            console.error('Ошибка генерации:', data.error);
            completeAssistantMessage();
            isGenerating = false;
            updateUIState();
        });
        // Добавьте обработчик события cancelled
        eventSource.addEventListener('cancelled', function (event) {
            const data = JSON.parse(event.data);
            if (data && data.cancelled) {
                console.log('Генерация отменена');
                completeAssistantMessage();
                isGenerating = false;
                updateUIState();
            }
        });

    } catch (error) {
        console.error('❌ Ошибка при подключении: ' + error.message);
        updateConnectionStatus('disconnected', 'Ошибка подключения: ' + error.message);
    }
}

function formatAssistantMessage(content) {
    // Обработка doc blocks (<doc>...</doc>)
    let formattedContent = content.replace(/<doc>\s*([\s\S]*?)\s*<\/doc>/g, (match, docContent) => {
        return `<div class="code-block-container">
                <div class="code-header">
                    <span class="language-label">document</span>
                </div>
                <pre><code class="language-text">${escapeHtml(docContent.trim())}</code></pre>
            </div>`;
    });

    // Обработка code blocks (```)
    formattedContent = formattedContent.replace(/```(\w+)?\s*([\s\S]*?)```/g, (match, lang, code) => {
        const language = lang || 'text';
        return `<div class="code-block-container">
                <div class="code-header">
                    <span class="language-label">${language}</span>
                </div>
                <pre><code class="language-${language}">${escapeHtml(code.trim())}</code></pre>
            </div>`;
    });

    // Обработка inline code (`code`)
    formattedContent = formattedContent.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>');

    // Basic formatting for markdown-like syntax
    formattedContent = formattedContent
        // Headers
        .replace(/^### (.*$)/gim, '<h3>$1</h3>')
        .replace(/^## (.*$)/gim, '<h2>$1</h2>')
        .replace(/^# (.*$)/gim, '<h1>$1</h1>')
        // Bold and italic
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        .replace(/\*(.*?)\*/g, '<em>$1</em>')
        // Lists
        .replace(/^- (.*$)/gim, '<li>$1</li>')
        .replace(/(<li>.*<\/li>)/gs, '<ul>$1</ul>')
        // Blockquotes
        .replace(/^> (.*$)/gim, '<blockquote>$1</blockquote>')
        // Horizontal rules
        .replace(/^---$/gim, '<hr>')
        // Paragraphs
        .replace(/\n\n/g, '</p><p>')
        .replace(/\n/g, '<br>');

    // Wrap in paragraph if no other formatting
    if (!formattedContent.includes('<h') &&
        !formattedContent.includes('<ul>') &&
        !formattedContent.includes('<blockquote>') &&
        !formattedContent.includes('<div class="code-block-container">')) {
        formattedContent = '<p>' + formattedContent + '</p>';
    }

    return formattedContent;
}

// Функция для экранирования HTML тегов
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function disconnectSSE() {
    if (eventSource) {
        eventSource.close();
        eventSource = null;
        console.log('🔌 Соединение закрыто');
        updateConnectionStatus('disconnected', 'Отключено от SSE');
    }
}

function updateConnectionStatus(status, message) {
    const statusElement = document.getElementById('connectionStatus');
    statusElement.className = 'status ' + status;
    statusElement.textContent = message;
}

function getOrCreateChatId() {
    let chatId = localStorage.getItem('currentChatId');
    if (!chatId) {
        chatId = Date.now().toString();
        localStorage.setItem('currentChatId', chatId);
    }
    return chatId;
}

function updateUIState() {
    const sendButton = document.getElementById('sendButton');
    const cancelButton = document.getElementById('cancelButton');
    const generateButton = document.getElementById('generateButton');

    if (isGenerating) {
        sendButton.style.display = 'none';
        generateButton.style.display = 'none';
        cancelButton.style.display = 'flex';
    } else {
        sendButton.style.display = 'flex';
        generateButton.style.display = 'flex';
        cancelButton.style.display = 'none';
    }
}

// Обновите функцию cancelGeneration
function cancelGeneration() {
    const chatId = getOrCreateChatId();

    // Отправляем запрос на отмену генерации
    fetch(`/api/cancel/${chatId}`, {
        method: 'POST'
    })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                console.log('Запрос на отмену отправлен');
            } else {
                console.error('Ошибка отмены:', data.error);
            }
        })
        .catch(error => {
            console.error('Ошибка при отправке запроса отмены:', error);
        });

    // Локальная отмена
    if (eventSource) {
        eventSource.close();
        eventSource = null;
    }
    completeAssistantMessage();
    isGenerating = false;
    updateUIState();
    connectSSE(); // Переподключаемся
}

// Функция для загрузки списка моделей
async function loadModels() {
    try {
        const response = await fetch('/api/models');
        if (!response.ok) {
            throw new Error('Ошибка загрузки моделей');
        }

        const data = await response.json();
        if (data.success) {
            populateModelSelects(data.models);
        }
    } catch (error) {
        console.error('Ошибка при загрузке моделей:', error);
    }
}

// Функция для заполнения выпадающих списков моделей
// Функция для заполнения выпадающих списков моделей
function populateModelSelects(models) {
    const chatModelSelect = document.getElementById('chatModel');
    const embeddingModelSelect = document.getElementById('embeddingModel');

    // Очищаем существующие опции
    chatModelSelect.innerHTML = '';
    embeddingModelSelect.innerHTML = '';

    // Добавляем модели в чат список (все модели)
    models.forEach(model => {
        const option = document.createElement('option');
        option.value = model.name;

        // Для чат моделей добавляем эмоджи если это модель для эмбеддингов
        let displayText = model.name;
        if (model.isEmbeddingModel) {
            displayText += ' 🧬'; // Эмоджи для эмбеддинг моделей
        }
        if (model.supportsImages) {
            displayText += ' 👁️'; // Эмоджи для vision моделей
        }

        option.textContent = displayText;
        option.title = `Размер: ${formatFileSize(model.size)}, Модифицирована: ${model.modified}`;

        if (model.isEmbeddingModel) {
            option.title += ', Модель для эмбеддингов';
        }
        if (model.supportsImages) {
            option.title += ', Поддерживает изображения';
        }

        chatModelSelect.appendChild(option);
    });

    // Добавляем только модели для эмбеддингов в соответствующий список
    models.filter(model => model.isEmbeddingModel).forEach(model => {
        const option = document.createElement('option');
        option.value = model.name;
        option.textContent = `${model.name} 🧬`; // Эмоджи для эмбеддинг моделей
        option.title = `Размер: ${formatFileSize(model.size)}, Модифицирована: ${model.modified}, Модель для эмбеддингов`;

        embeddingModelSelect.appendChild(option);
    });

    // Устанавливаем выбранные значения из localStorage, если есть
    const savedChatModel = localStorage.getItem('selectedChatModel');
    const savedEmbeddingModel = localStorage.getItem('selectedEmbeddingModel');

    if (savedChatModel) {
        chatModelSelect.value = savedChatModel;
    }

    if (savedEmbeddingModel) {
        embeddingModelSelect.value = savedEmbeddingModel;
    }
}

// Вспомогательная функция для форматирования размера файла
function formatFileSize(bytes) {
    if (bytes === 0 || bytes === undefined || bytes === null) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));

    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// Обновляем функцию openSettings для загрузки моделей при открытии
function openSettings() {
    document.getElementById('settingsModal').style.display = 'block';

    // Загружаем сохраненные промты
    const savedSystemPrompt = localStorage.getItem('systemPrompt');
    const savedGeneratePrompt = localStorage.getItem('generatePrompt');

    if (savedSystemPrompt) {
        document.getElementById('systemPrompt').value = savedSystemPrompt;
    }

    if (savedGeneratePrompt) {
        document.getElementById('generatePrompt').value = savedGeneratePrompt;
    }
}

// Функция для сохранения настроек
function saveSettings() {
    const chatModel = document.getElementById('chatModel').value;
    const embeddingModel = document.getElementById('embeddingModel').value;
    const systemPrompt = document.getElementById('systemPrompt').value;
    const generatePrompt = document.getElementById('generatePrompt').value;

    // Сохраняем в localStorage
    localStorage.setItem('selectedChatModel', chatModel);
    localStorage.setItem('selectedEmbeddingModel', embeddingModel);
    localStorage.setItem('systemPrompt', systemPrompt);
    localStorage.setItem('generatePrompt', generatePrompt);

    // Закрываем модальное окно
    document.getElementById('settingsModal').style.display = 'none';

    console.log('Настройки сохранены:', {
        chatModel,
        embeddingModel,
        systemPrompt,
        generatePrompt
    });
}

// Функция для закрытия настроек
function closeSettings() {
    document.getElementById('settingsModal').style.display = 'none';
}

// Функция для загрузки настроек при запуске приложения
async function loadSettingsOnStartup() {
    try {
        // Пытаемся загрузить с сервера
        await loadSettingsFromServer();
    } catch (error) {
        console.log('Сервер настроек недоступен, используем localStorage');
        // Используем localStorage как fallback
        const savedChatModel = localStorage.getItem('selectedChatModel');
        const savedEmbeddingModel = localStorage.getItem('selectedEmbeddingModel');
        const savedSystemPrompt = localStorage.getItem('systemPrompt');
        const savedGeneratePrompt = localStorage.getItem('generatePrompt');

        if (savedChatModel) {
            document.getElementById('chatModel').value = savedChatModel;
        }

        if (savedEmbeddingModel) {
            document.getElementById('embeddingModel').value = savedEmbeddingModel;
        }
    }
}

// Функция для сохранения настроек на сервере
async function saveSettingsToServer() {
    const chatModel = document.getElementById('chatModel').value;
    const embeddingModel = document.getElementById('embeddingModel').value;
    const systemPrompt = document.getElementById('systemPrompt').value;
    const generatePrompt = document.getElementById('generatePrompt').value;

    const settings = {
        chatModel,
        embeddingModel,
        systemPrompt,
        generatePrompt
    };

    try {
        const response = await fetch('/api/setup/save', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(settings)
        });

        const data = await response.json();
        if (data.success) {
            console.log('Настройки сохранены на сервере:', data.savedSettings);
        } else {
            console.error('Ошибка сохранения настроек на сервере:', data.error);
        }
    } catch (error) {
        console.error('Ошибка при сохранении настроек на сервере:', error);
    }
}

// Функция для загрузки настроек с сервера
async function loadSettingsFromServer() {
    try {
        const response = await fetch('/api/setup/load');
        const data = await response.json();

        if (data.success && data.settings) {
            const settings = data.settings;

            // Заполняем поля настроек
            if (settings.chatModel) {
                document.getElementById('chatModel').value = settings.chatModel;
                localStorage.setItem('selectedChatModel', settings.chatModel);
            }

            if (settings.embeddingModel) {
                document.getElementById('embeddingModel').value = settings.embeddingModel;
                localStorage.setItem('selectedEmbeddingModel', settings.embeddingModel);
            }

            if (settings.systemPrompt) {
                document.getElementById('systemPrompt').value = settings.systemPrompt;
                localStorage.setItem('systemPrompt', settings.systemPrompt);
            }

            if (settings.generatePrompt) {
                document.getElementById('generatePrompt').value = settings.generatePrompt;
                localStorage.setItem('generatePrompt', settings.generatePrompt);
            }

            console.log('Настройки загружены с сервера:', settings);
        }
    } catch (error) {
        console.error('Ошибка при загрузке настроек с сервера:', error);
    }
}

// Обновляем функцию saveSettings для сохранения на сервере
async function saveSettings() {
    const chatModel = document.getElementById('chatModel').value;
    const embeddingModel = document.getElementById('embeddingModel').value;
    const systemPrompt = document.getElementById('systemPrompt').value;
    const generatePrompt = document.getElementById('generatePrompt').value;

    // Сохраняем в localStorage
    localStorage.setItem('selectedChatModel', chatModel);
    localStorage.setItem('selectedEmbeddingModel', embeddingModel);
    localStorage.setItem('systemPrompt', systemPrompt);
    localStorage.setItem('generatePrompt', generatePrompt);

    // Сохраняем на сервере
    await saveSettingsToServer();

    // Закрываем модальное окно
    document.getElementById('settingsModal').style.display = 'none';

    console.log('Настройки сохранены:', {
        chatModel,
        embeddingModel,
        systemPrompt,
        generatePrompt
    });
}

// Обновляем функцию openSettings для загрузки настроек с сервера
async function openSettings() {
    document.getElementById('settingsModal').style.display = 'block';

    // Загружаем модели
    //await loadModels();

    // Загружаем настройки с сервера
    await loadSettingsFromServer();

    // Дублируем из localStorage (на случай если сервер недоступен)
    const savedChatModel = localStorage.getItem('selectedChatModel');
    const savedEmbeddingModel = localStorage.getItem('selectedEmbeddingModel');
    const savedSystemPrompt = localStorage.getItem('systemPrompt');
    const savedGeneratePrompt = localStorage.getItem('generatePrompt');

    if (savedChatModel) {
        document.getElementById('chatModel').value = savedChatModel;
    }

    if (savedEmbeddingModel) {
        document.getElementById('embeddingModel').value = savedEmbeddingModel;
    }

    if (savedSystemPrompt) {
        document.getElementById('systemPrompt').value = savedSystemPrompt;
    }

    if (savedGeneratePrompt) {
        document.getElementById('generatePrompt').value = savedGeneratePrompt;
    }
}

// Инициализация
document.addEventListener('DOMContentLoaded', function () {
    // Обработчики кнопок
    document.getElementById('sendButton').addEventListener('click', sendMessage);
    document.getElementById('generateButton').addEventListener('click', generateText);
    document.getElementById('cancelButton').addEventListener('click', cancelGeneration);
    document.getElementById('clearChat').addEventListener('click', clearChat);
    document.getElementById('settingsBtn').addEventListener('click', () => openSettings());

    // Обработчики для настроек
    document.getElementById('saveSettings').addEventListener('click', saveSettings);
    document.getElementById('closeSettings').addEventListener('click', closeSettings);
    // Загрузка настроек при запуске
    loadSettingsOnStartup();

    // Инициализация загрузки документов
    initDocumentUpload();
    // Enter для отправки
    document.getElementById('messageInput').addEventListener('keypress', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    // Drag and drop обработчики
    const dropZone = document.getElementById('dropZone');
    const fileInput = document.getElementById('fileInput');

    // Клик по drop zone открывает файловый диалог
    dropZone.addEventListener('click', () => {
        fileInput.click();
    });

    // Обработка выбора файлов через input
    fileInput.addEventListener('change', (e) => {
        handleFileSelect(e.target.files);
        fileInput.value = '';
    });

    // Drag and drop события
    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropZone.classList.add('dragover');
    });

    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('dragover');
    });

    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.classList.remove('dragover');

        if (e.dataTransfer.files.length > 0) {
            handleFileSelect(e.dataTransfer.files);
        }
    });

    // Автоподключение при загрузке
    connectSSE();

    // Загрузка истории чата при загрузке страницы
    loadChatHistory();

    // Инициализация состояния кнопки очистки
    updateClearChatButton();

    // Загружаем модели при открытии настроек
    loadModels();


    // Автоматическое отключение при закрытии страницы
    window.addEventListener('beforeunload', function () {
        disconnectSSE();
    });
});

// Функция для загрузки истории чата
async function loadChatHistory() {
    const chatId = getOrCreateChatId();

    try {
        const response = await fetch(`/api/history/${chatId}`);
        if (!response.ok) {
            throw new Error('Ошибка загрузки истории');
        }

        const data = await response.json();
        const history = data.history;

        // Очищаем текущий чат
        document.getElementById('chatMessages').innerHTML = '';
        currentAssistantMessage = null;

        // Восстанавливаем историю сообщений
        history.forEach(message => {
            if (message.role === 'user') {
                addMessage(message.content, true);
            } else if (message.role === 'assistant') {
                // Для сообщений ассистента создаем уже отформатированное сообщение
                const messageDiv = document.createElement('div');
                messageDiv.className = 'message assistant-message';

                const contentDiv = document.createElement('div');
                contentDiv.className = 'streaming-content';
                contentDiv.innerHTML = formatAssistantMessage(message.content);

                messageDiv.appendChild(contentDiv);
                document.getElementById('chatMessages').appendChild(messageDiv);
            }
        });

        console.log(`Загружено ${history.length} сообщений из истории`);

        // Обновляем состояние кнопки очистки
        updateClearChatButton();

    } catch (error) {
        console.error('Ошибка при загрузке истории:', error);
    }
}

// Функция для очистки истории чата через эндпоинт
async function clearChatHistory() {
    const chatId = getOrCreateChatId();
    try {
        const response = await fetch(`/api/history/${chatId}`, {
            method: 'DELETE'
        });
        if (!response.ok) {
            throw new Error('Ошибка очистки истории');
        }
        const data = await response.json();
        console.log(data.message);
        document.getElementById('chatMessages').innerHTML = '';
    } catch (error) {
        console.error('Ошибка при очистке истории:', error);
    }
}

// Обновляем функцию clearChat для использования эндпоинта
function clearChat() {
    clearChatHistory(); // Теперь используем эндпоинт для очистки
}

// Функция для обновления кнопки очистки чата
function updateClearChatButton() {
    const clearChatBtn = document.getElementById('clearChat');
    const chatMessages = document.getElementById('chatMessages');

    // Показываем кнопку только если есть сообщения
    if (chatMessages.children.length > 0) {
        clearChatBtn.style.display = 'block';
    } else {
        clearChatBtn.style.display = 'none';
    }
}