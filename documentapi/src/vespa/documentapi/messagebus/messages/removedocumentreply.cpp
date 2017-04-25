// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "removedocumentreply.h"
#include <vespa/documentapi/messagebus/documentprotocol.h>

namespace documentapi {

RemoveDocumentReply::RemoveDocumentReply() :
    WriteDocumentReply(DocumentProtocol::REPLY_REMOVEDOCUMENT),
    _found(true)
{
    // empty
}

}
