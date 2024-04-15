import aws from 'aws-sdk';
import awsXRay from 'aws-xray-sdk';
import {DynamoDBClient} from "@aws-sdk/client-dynamodb";
import {
  DynamoDBDocumentClient,
  PutCommand,
} from "@aws-sdk/lib-dynamodb";

const client = new DynamoDBClient({});
const dynamo = DynamoDBDocumentClient.from(client);
const tableName = "ProductTable";

awsXRay.captureAWS(aws);

export async function handler(product) {
  console.log('Received Step Functions event:', JSON.stringify(product, null, 2));

  let statusCode = 200;
  let body;
  try {

    body = {
      productId: product.id,
      category: product.category
    };

    const segment = awsXRay.getSegment();
    const subSegment = segment.addNewSubsegment('PutEventInDynamoDb');
    subSegment.addAnnotation('product id', product.id);
    subSegment.addMetadata('product', product);

    await dynamo.send(
        new PutCommand({
          TableName: tableName,
          Item: {
            PK: 'PRODUCT#' + product.id,
            SK: 'PRODUCT#' + product.id,
            name: product.name,
            description: product.description,
            category: product.category,
            price: product.price,
            'GS1-PK': 'CATEGORY#' + product.category,
            'GS1-SK': 'PRODUCT#' + product.id
          },
        })
    );
    console.log("Product saved");
    subSegment.close();
  } catch (err) {
    console.log(err.message);
    statusCode = 500;
  }
  const headers = {
    "Access-Control-Allow-Headers" : "Content-Type",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "OPTIONS,POST"
  };
  return {statusCode, body, headers};
}